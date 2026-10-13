package com.knowflow.application.document.service.impl;

import com.knowflow.application.document.api.DocumentStateApi;
import com.knowflow.application.document.model.entity.DocumentEntity;
import com.knowflow.application.document.repository.DocumentRepository;
import com.knowflow.application.document.statemachine.DocumentStateEnum;
import com.knowflow.application.document.statemachine.DocumentStateTransitionEvent;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 文档状态管理跨模块命令契约实现类 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentStateApiImpl implements DocumentStateApi {

  private final DocumentRepository documentRepository;
  private final ApplicationEventPublisher eventPublisher;

  @Override
  @Transactional
  public void rollbackState(Long documentId, DocumentStateEnum targetState, String reason) {
    if (documentId == null || targetState == null) {
      return;
    }

    DocumentEntity doc = documentRepository.findById(documentId).orElse(null);
    if (doc == null) {
      log.warn("【DocumentStateApi】回滚文档状态失败，文档不存在: docId={}", documentId);
      return;
    }

    DocumentStateEnum fromState = doc.getStatus();
    if (fromState == targetState) {
      log.info(
          "【DocumentStateApi】文档当前状态已为目标状态，无需重复变更: docId={}, state={}", documentId, targetState);
      return;
    }

    log.warn(
        "【DocumentStateApi】触发文档状态回滚: docId={}, fromState={}, targetState={}, reason={}",
        documentId,
        fromState,
        targetState,
        reason);

    if (targetState == DocumentStateEnum.PENDING_APPROVAL) {
      doc.revertToPendingApproval();
    } else {
      doc.transitionTo(targetState);
    }
    documentRepository.save(doc);

    // 发布状态流转事件，通知下游（如 ES 全文检索将该文档从上线索引中移除）
    eventPublisher.publishEvent(
        new DocumentStateTransitionEvent(
            documentId, fromState, targetState, null, 0L, reason, LocalDateTime.now()));
  }
}
