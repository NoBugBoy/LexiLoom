package io.lexiloom.application.ai.listener;

import io.lexiloom.application.ai.chunk.DeepDocContextEnricher;
import io.lexiloom.application.ai.chunk.EnrichedDocChunk;
import io.lexiloom.application.ai.gateway.embedding.EmbeddingGateway;
import io.lexiloom.application.ai.model.search.DocChunkDocument;
import io.lexiloom.application.ai.repository.DocChunkRepository;
import io.lexiloom.application.ai.trace.RagTraceHolder;
import io.lexiloom.application.ai.trace.model.RagTraceContext;
import io.lexiloom.application.document.api.DocumentQueryApi;
import io.lexiloom.application.document.api.DocumentStateApi;
import io.lexiloom.application.document.api.dto.DocSourceFileDTO;
import io.lexiloom.application.document.api.dto.DocumentDTO;
import io.lexiloom.application.document.model.event.DocumentDeletedEvent;
import io.lexiloom.application.document.model.event.DocumentUpdatedEvent;
import io.lexiloom.application.document.statemachine.DocumentStateEnum;
import io.lexiloom.application.document.statemachine.DocumentStateTransitionEvent;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 异步切块与向量入库监听器 (DocVectorSyncListener)
 *
 * <p>响应文档生命周期与状态流转事件，编排带帽子的原子切块生成、Spring AI 批量向量化与 ES 8.x 密集向量索引落库：
 *
 * <ul>
 *   <li>{@code APPROVED}（审批通过/发布）：组装带帽子切块，批量调用 {@link EmbeddingGateway} 向量化，原子写入 ES；
 *   <li>{@code REJECTED} / {@code PENDING_APPROVAL} / 文档删除：物理清理对应文档在 ES 中的所有向量切块。
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocVectorSyncListener {

  private final DocumentQueryApi documentQueryApi;
  private final DocumentStateApi documentStateApi;
  private final DeepDocContextEnricher deepDocContextEnricher;
  private final EmbeddingGateway embeddingGateway;
  private final DocChunkRepository docChunkRepository;

  /** 监听状态机流转事件：驱动向量索引上线与下线 */
  @ApplicationModuleListener
  public void onDocumentStateTransition(DocumentStateTransitionEvent event) {
    if (event == null || event.documentId() == null || event.targetState() == null) {
      return;
    }

    log.info(
        "【DocVectorSyncListener】接收到文档状态流转事件: docId={}, fromState={}, targetState={}",
        event.documentId(),
        event.fromState(),
        event.targetState());

    if (event.targetState() == DocumentStateEnum.APPROVED) {
      try {
        syncDocumentChunks(event.documentId());
      } catch (Exception e) {
        log.error(
            "【DocVectorSyncListener】审批通过后切块与向量写入异常，系统自动回滚文档状态为待审批: docId={}, error={}",
            event.documentId(),
            e.getMessage(),
            e);
        // 1. 物理清理可能残留的 ES 向量切片
        removeDocumentChunks(event.documentId(), null);
        // 2. 状态回滚为待审批并发布事件让全文检索下线 (对异常摘要进行安全截断)
        String rawErr = e.getMessage() != null ? e.getMessage() : "未知异常";
        String safeSummary = cn.hutool.core.util.StrUtil.sub(rawErr, 0, 120);
        documentStateApi.rollbackState(
            event.documentId(),
            DocumentStateEnum.PENDING_APPROVAL,
            "异步切块与向量写入异常，系统自动恢复为待审批: " + safeSummary);
      }
    } else {
      removeDocumentChunks(event.documentId(), null);
    }
  }

  /** 监听文档物理删除事件：安全清理切片索引 */
  @ApplicationModuleListener
  public void onDocumentDeleted(DocumentDeletedEvent event) {
    if (event == null || event.documentId() == null) {
      return;
    }

    log.info(
        "【DocVectorSyncListener】接收到文档删除事件，开始清理向量索引: docId={}, workspaceId={}",
        event.documentId(),
        event.workSpaceId());

    removeDocumentChunks(event.documentId(), event.workSpaceId());
  }

  /** 监听文档内容更新事件：已发布文档重新计算向量并同步索引 */
  @ApplicationModuleListener
  public void onDocumentUpdated(DocumentUpdatedEvent event) {
    if (event == null || event.documentId() == null) {
      return;
    }

    documentQueryApi
        .findDocumentById(event.documentId())
        .filter(doc -> doc.status() == DocumentStateEnum.APPROVED)
        .ifPresent(
            doc -> {
              log.info("【DocVectorSyncListener】已发布文档发生更新，重新同步向量索引: docId={}", event.documentId());
              syncDocumentChunks(event.documentId());
            });
  }

  /**
   * 执行切块加冠与向量入库全流程
   *
   * @param documentId 文档主键 ID
   */
  public void syncDocumentChunks(Long documentId) {
    RagTraceContext traceContext = new RagTraceContext(null, null, null, null, null);
    ScopedValue.where(RagTraceHolder.CURRENT, traceContext)
        .run(() -> syncDocumentChunksWithUsage(documentId, traceContext));
  }

  private void syncDocumentChunksWithUsage(Long documentId, RagTraceContext traceContext) {
    long startTime = System.currentTimeMillis();

    // 1. 查询文档详情
    DocumentDTO doc = documentQueryApi.findDocumentById(documentId).orElse(null);
    if (doc == null) {
      log.warn("【DocVectorSyncListener】同步切块跳过：未找到对应文档, docId={}", documentId);
      return;
    }

    if (doc.status() != DocumentStateEnum.APPROVED) {
      log.warn(
          "【DocVectorSyncListener】同步切块跳过：文档当前非审批通过状态, docId={}, status={}",
          documentId,
          doc.status());
      return;
    }

    // 2. 查询关联来源文件
    DocSourceFileDTO sourceFile = null;
    if (doc.sourceFileId() != null) {
      sourceFile = documentQueryApi.findSourceFileById(doc.sourceFileId()).orElse(null);
    }
    if (sourceFile == null) {
      sourceFile = documentQueryApi.findSourceFileByDocumentId(documentId).orElse(null);
    }

    // 3. 执行 DeepDoc 上下文帽子组装 (原子段落严格 1:1，绝无滑动窗口与 Overlap)
    List<EnrichedDocChunk> enrichedChunks = deepDocContextEnricher.enrich(doc, sourceFile);
    if (enrichedChunks.isEmpty()) {
      log.warn("【DocVectorSyncListener】文档内容为空或未提取到有效段落，跳过切片入库: docId={}", documentId);
      return;
    }

    // 4. 批量调用 Spring AI EmbeddingGateway 计算向量 (单批 16 条，保守满足各大模型如千问 batch <= 20 限制)
    List<String> textsToEmbed =
        enrichedChunks.stream().map(EnrichedDocChunk::enrichedText).toList();
    List<float[]> vectors = new ArrayList<>(textsToEmbed.size());
    int embedBatchSize = 16;
    try {
      for (int i = 0; i < textsToEmbed.size(); i += embedBatchSize) {
        int end = Math.min(i + embedBatchSize, textsToEmbed.size());
        vectors.addAll(embeddingGateway.embedBatch(textsToEmbed.subList(i, end)));
      }
    } finally {
      // 即使后续分批调用失败，也必须保留此前已成功调用并产生的实际 Token 消耗。
      if (traceContext.getEmbeddingTokens() > 0) {
        documentStateApi.recordEmbeddingTokens(documentId, traceContext.getEmbeddingTokens());
      }
    }

    // 4.1 向量结果强校验：条数与维度必须严格对齐，否则宁可快速失败回滚文档状态，
    //     也不能用零向量兜底把错位/残缺向量写进索引 (零向量会让 kNN 相似度彻底失真)
    validateVectors(textsToEmbed.size(), vectors);

    // 5. 构造 Elasticsearch 切块实体列表
    List<DocChunkDocument> chunkDocuments = new ArrayList<>(enrichedChunks.size());
    String wsIdStr = String.valueOf(doc.workSpaceId());
    String docIdStr = String.valueOf(doc.id());

    for (int i = 0; i < enrichedChunks.size(); i++) {
      EnrichedDocChunk chunk = enrichedChunks.get(i);
      float[] vector = vectors.get(i);

      DocChunkDocument esChunk =
          DocChunkDocument.builder()
              .id(DocChunkDocument.generateId(doc.id(), chunk.chunkIndex()))
              .workspaceId(wsIdStr)
              .documentId(docIdStr)
              .chunkIndex(chunk.chunkIndex())
              .chunkId(chunk.chunkId())
              .title(chunk.title())
              .headerPath(chunk.headerPath())
              .content(chunk.content())
              .contextPrefix(chunk.contextPrefix())
              .contentVector(vector)
              .page(chunk.page())
              .bbox(chunk.bbox())
              .chunkType(chunk.chunkType())
              .anchor(chunk.anchor())
              .categoryId(doc.categoryId() != null ? String.valueOf(doc.categoryId()) : null)
              .visibility(
                  doc.visibility() != null
                      ? doc.visibility().name()
                      : WorkSpaceAclEnum.INTERNAL.name())
              .createBy(doc.createBy() != null ? String.valueOf(doc.createBy()) : null)
              .updateTime(LocalDateTime.now())
              .build();

      chunkDocuments.add(esChunk);
    }

    try {
      // 7. 先清空旧切块再全量写入，保障幂等性与数据一致性
      docChunkRepository.deleteByWorkspaceIdAndDocumentId(wsIdStr, docIdStr);

      // 分批写入 Elasticsearch，每批 100 条切块，严防单次 Bulk 体积过大触发 HTTP 413 Payload Too Large
      int esBatchSize = 100;
      for (int i = 0; i < chunkDocuments.size(); i += esBatchSize) {
        int end = Math.min(i + esBatchSize, chunkDocuments.size());
        docChunkRepository.saveAll(chunkDocuments.subList(i, end));
      }

      long cost = System.currentTimeMillis() - startTime;
      log.info(
          "【DocVectorSyncListener】切块与向量入库成功: docId={}, title=[{}], 切块总数={}, 耗时={}ms",
          documentId,
          doc.title(),
          chunkDocuments.size(),
          cost);
    } catch (Exception e) {
      log.error(
          "【DocVectorSyncListener】切块与向量写入 ES 异常: docId={}, error={}",
          documentId,
          e.getMessage(),
          e);
      throw e;
    }
  }

  /**
   * 校验批量向量化结果与入参文本严格一一对应
   *
   * @param expectedCount 期望的向量条数 (与入参文本条数一致)
   * @param vectors 实际返回的向量列表
   * @throws IllegalStateException 条数缺失、维度不一致或出现空向量时抛出，触发文档状态回滚
   */
  private void validateVectors(int expectedCount, List<float[]> vectors) {
    if (vectors.size() != expectedCount) {
      throw new IllegalStateException(
          String.format("向量化结果条数与切块数量不一致，禁止写入索引: 期望=%d, 实际=%d", expectedCount, vectors.size()));
    }

    int expectedDimension = embeddingGateway.getDimension();
    for (int i = 0; i < vectors.size(); i++) {
      float[] vector = vectors.get(i);
      if (vector == null || vector.length == 0) {
        throw new IllegalStateException(String.format("第 %d 个切块未返回有效向量 (空向量)，禁止写入索引", i));
      }
      if (expectedDimension > 0 && vector.length != expectedDimension) {
        throw new IllegalStateException(
            String.format("第 %d 个切块向量维度与配置不一致: 期望=%d, 实际=%d", i, expectedDimension, vector.length));
      }
    }
  }

  /** 物理下线并删除文档切块 */
  public void removeDocumentChunks(Long documentId, Long workSpaceId) {
    if (documentId == null) {
      return;
    }

    String wsIdStr = null;
    if (workSpaceId != null) {
      wsIdStr = String.valueOf(workSpaceId);
    } else {
      DocumentDTO doc = documentQueryApi.findDocumentById(documentId).orElse(null);
      if (doc != null) {
        wsIdStr = String.valueOf(doc.workSpaceId());
      }
    }

    if (wsIdStr == null) {
      wsIdStr = "";
    }

    try {
      docChunkRepository.deleteByWorkspaceIdAndDocumentId(wsIdStr, String.valueOf(documentId));
      log.info(
          "【DocVectorSyncListener】文档切块已成功从 ES 物理移除: docId={}, workspaceId={}", documentId, wsIdStr);
    } catch (Exception e) {
      log.error(
          "【DocVectorSyncListener】从 ES 删除文档切块异常: docId={}, error={}",
          documentId,
          e.getMessage(),
          e);
    }
  }
}
