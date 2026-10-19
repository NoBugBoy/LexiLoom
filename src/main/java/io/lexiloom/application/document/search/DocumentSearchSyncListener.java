package io.lexiloom.application.document.search;

import io.lexiloom.application.document.mapper.DocSearchMapper;
import io.lexiloom.application.document.model.entity.DocSourceFileEntity;
import io.lexiloom.application.document.model.entity.DocumentEntity;
import io.lexiloom.application.document.model.event.DocumentDeletedEvent;
import io.lexiloom.application.document.model.event.DocumentUpdatedEvent;
import io.lexiloom.application.document.repository.DocSourceFileRepository;
import io.lexiloom.application.document.repository.DocumentRepository;
import io.lexiloom.application.document.statemachine.DocumentStateEnum;
import io.lexiloom.application.document.statemachine.DocumentStateTransitionEvent;
import java.util.Collections;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Elasticsearch 索引同步异步事件监听器
 *
 * <p>监听文档状态机流转事件 {@link DocumentStateTransitionEvent}，驱动 Elasticsearch 全文检索索引的生命周期变更：
 *
 * <ul>
 *   <li>{@code APPROVED}: 读取文档主体、关联源文件原始纯文本及标签，写入/覆盖 ES 索引
 *   <li>{@code REJECTED} / {@code PENDING_APPROVAL}: 从 ES 索引中安全移除文档
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentSearchSyncListener {

  private final DocumentRepository documentRepository;
  private final DocSourceFileRepository docSourceFileRepository;
  private final DocSearchRepository docSearchRepository;
  private final DocSearchMapper docSearchMapper;

  @ApplicationModuleListener
  public void onDocumentStateTransition(DocumentStateTransitionEvent event) {
    if (event == null || event.documentId() == null || event.targetState() == null) {
      return;
    }

    log.info(
        "收到文档状态流转事件，准备同步 ES 索引: documentId={}, fromState={}, toState={}",
        event.documentId(),
        event.fromState(),
        event.targetState());

    switch (event.targetState()) {
      case APPROVED -> indexDocument(event.documentId());
      case REJECTED, PENDING_APPROVAL -> removeFromIndex(event.documentId());
      default ->
          log.debug(
              "忽略非索引关心的状态变更: documentId={}, toState={}", event.documentId(), event.targetState());
    }
  }

  /**
   * 监听文档删除领域事件，在事务提交后从 ES 检索索引中彻底移除该文档
   *
   * @param event 文档删除事件
   */
  @ApplicationModuleListener
  public void onDocumentDeleted(DocumentDeletedEvent event) {
    if (event == null || event.documentId() == null) {
      return;
    }
    log.info("收到文档删除事件，准备从 ES 索引移除: documentId={}", event.documentId());
    removeFromIndex(event.documentId());
  }

  /**
   * 监听文档内容更新事件（如正文变更、版本回退等），在事务提交后重新全量同步该文档索引
   *
   * @param event 文档更新事件
   */
  @ApplicationModuleListener
  public void onDocumentUpdated(DocumentUpdatedEvent event) {
    if (event == null || event.documentId() == null) {
      return;
    }
    log.info("收到文档内容更新事件，准备刷新 ES 索引: documentId={}", event.documentId());
    indexDocument(event.documentId());
  }

  /**
   * 同步文档至 Elasticsearch 核心索引
   *
   * @param documentId 文档主键 ID
   */
  public void indexDocument(Long documentId) {
    try {
      // 1. 查询文档聚合根并校验已发布状态
      DocumentEntity doc = documentRepository.findById(documentId).orElse(null);

      if (doc == null) {
        log.warn("ES 索引同步跳过：未找到对应文档记录, documentId={}", documentId);
        return;
      }

      if (doc.getStatus() != DocumentStateEnum.APPROVED) {
        log.warn(
            "ES 索引同步跳过：文档当前非审批通过状态, documentId={}, currentStatus={}", documentId, doc.getStatus());
        return;
      }

      // 2. 查询关联的源文件纯文本 (可选)
      DocSourceFileEntity sourceFile =
          docSourceFileRepository
              .findByDocumentId(documentId)
              .or(
                  () ->
                      doc.getSourceFileId() != null
                          ? docSourceFileRepository.findById(doc.getSourceFileId())
                          : java.util.Optional.empty())
              .orElse(null);

      // 3. 查询文档关联标签名 (可选)
      //      List<String> tags = resolveTagNames(documentId);

      // 4. 映射为 ES 检索文档实体
      DocSearchDocument searchDoc =
          docSearchMapper.toSearchDoc(doc, sourceFile, Collections.emptyList());

      // 5. 保存至 ES
      docSearchRepository.save(searchDoc);

      log.info(
          "成功同步文档至 Elasticsearch 索引: documentId={}, title={}, hasRawText={}",
          documentId,
          doc.getTitle(),
          sourceFile != null && sourceFile.getRawText() != null);
    } catch (Exception ex) {
      log.error("Elasticsearch 同步文档索引异常: documentId={}, error={}", documentId, ex.getMessage(), ex);
    }
  }

  /**
   * 从 Elasticsearch 核心索引中删除文档
   *
   * @param documentId 文档主键 ID
   */
  public void removeFromIndex(Long documentId) {
    try {
      String id = String.valueOf(documentId);
      docSearchRepository.deleteById(id);
      log.info("成功从 Elasticsearch 索引中移除文档: documentId={}", documentId);
    } catch (Exception ex) {
      log.error("Elasticsearch 移除文档索引异常: documentId={}, error={}", documentId, ex.getMessage(), ex);
    }
  }
}
