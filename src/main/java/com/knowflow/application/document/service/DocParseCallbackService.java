package com.knowflow.application.document.service;

import cn.hutool.core.util.StrUtil;
import com.knowflow.application.common.ErrorCode;
import com.knowflow.application.document.model.entity.DocumentEntity;
import com.knowflow.application.document.model.event.DocumentUpdatedEvent;
import com.knowflow.application.document.model.request.DeepDocParseCallbackRequest;
import com.knowflow.application.document.parser.DocParserEngineEnum;
import com.knowflow.application.document.parser.DocumentContentCleaner;
import com.knowflow.application.document.parser.engine.DeepDocDocParserEngine;
import com.knowflow.application.document.pipeline.DocumentImportTransactionalService;
import com.knowflow.application.document.repository.DocumentRepository;
import com.knowflow.application.exception.BusinessException;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * DeepDoc 异步解析结果 HTTP 回调业务编排服务
 * 负责接收 DeepDoc 解析完成通知，编排临时插图转存至 RustFS、更新文档正文状态与发布检索同步领域事件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocParseCallbackService {

  private final DocumentRepository documentRepository;
  private final DocumentImportTransactionalService transactionalService;
  private final DeepDocDocParserEngine deepDocParserEngine;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * 处理 DeepDoc 异步解析 HTTP 回调
   *
   * @param request 回调请求载荷
   */
  public void handleCallback(DeepDocParseCallbackRequest request) {
    if (request == null || request.documentId() == null) {
      log.warn("【DocParseCallbackService】收到无效的回调请求: request={}", request);
      throw new BusinessException(ErrorCode.Common.BAD_REQUEST, "documentId 不能为空");
    }

    Long docId = request.documentId();
    Long sourceFileId = request.sourceFileId();

    DocumentEntity doc = documentRepository.findById(docId).orElse(null);
    if (doc == null) {
      log.warn("【DocParseCallbackService】回调对应的文档不存在: docId={}", docId);
      return;
    }

    // 若未传入 sourceFileId，尝试使用文档关联的 sourceFileId
    Long effectiveSourceFileId = sourceFileId != null ? sourceFileId : doc.getSourceFileId();
    Long workspaceId = request.workspaceId() != null ? request.workspaceId() : doc.getWorkSpaceId();
    Long operatorId =
        request.operatorId() != null
            ? request.operatorId()
            : (doc.getCreateBy() != null ? doc.getCreateBy() : 0L);

    // 1. 若解析失败通知
    if (!request.isSuccess()) {
      String errorMsg =
          StrUtil.blankToDefault(
              request.error(),
              StrUtil.blankToDefault(request.message(), "DeepDoc 远程微服务异步解析失败"));
      log.warn(
          "【DocParseCallbackService】收到解析失败通知: docId={}, sourceFileId={}, error={}",
          docId,
          effectiveSourceFileId,
          errorMsg);
      transactionalService.failAsyncParse(docId, effectiveSourceFileId, errorMsg);
      return;
    }

    log.info(
        "【DocParseCallbackService】收到解析成功通知: docId={}, sourceFileId={}, taskId={}",
        docId,
        effectiveSourceFileId,
        request.taskId());

    // 2. 将临时插图转存至 RustFS 对象存储（耗时 IO 操作，隔离在事务外）
    String rawMarkdown = StrUtil.blankToDefault(request.markdown(), request.content());
    String markdownWithStorageUrls = deepDocParserEngine.transferFiguresToStorage(rawMarkdown);
    String cleanedMarkdown = DocumentContentCleaner.cleanEmbeddedImages(markdownWithStorageUrls);
    String rawText = DocumentContentCleaner.extractRawText(cleanedMarkdown);

    String fallbackTitle =
        StrUtil.isNotBlank(request.filename())
            ? StrUtil.subBefore(request.filename(), ".", true)
            : doc.getTitle();
    String suggestedTitle =
        DeepDocDocParserEngine.extractTitleFromMarkdown(cleanedMarkdown, fallbackTitle);

    // 3. 构建解析元数据
    Map<String, Object> metadata = new HashMap<>();
    if (StrUtil.isNotBlank(request.filename())) {
      metadata.put("fileName", request.filename());
    }
    if (StrUtil.isNotBlank(request.fileType())) {
      metadata.put("fileType", request.fileType());
    }
    if (StrUtil.isNotBlank(request.taskId())) {
      metadata.put("taskId", request.taskId());
    }
    metadata.put("engine", DocParserEngineEnum.DEEPDOC.name());
    metadata.put("wordCount", cleanedMarkdown != null ? cleanedMarkdown.length() : 0);
    if (request.chunks() != null && !request.chunks().isEmpty()) {
      metadata.put("chunks", request.chunks());
      metadata.put("totalChunks", request.chunks().size());
    }
    if (request.blocks() != null && !request.blocks().isEmpty()) {
      metadata.put("blocks", request.blocks());
    }

    // 4. 独立小事务持久化落库
    transactionalService.completeAsyncParse(
        docId,
        effectiveSourceFileId,
        cleanedMarkdown,
        rawText,
        suggestedTitle,
        DocParserEngineEnum.DEEPDOC,
        metadata);

    // 5. 发布领域事件，触发下游 Elasticsearch 检索索引与向量切片知识库同步
    eventPublisher.publishEvent(new DocumentUpdatedEvent(docId, workspaceId, operatorId));

    log.info(
        "【DocParseCallbackService】异步解析结果处理落库完成并已触发检索向量同步: docId={}, title={}, wordCount={}",
        docId,
        suggestedTitle,
        cleanedMarkdown != null ? cleanedMarkdown.length() : 0);
  }
}
