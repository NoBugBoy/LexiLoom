package io.lexiloom.application.document.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lexiloom.application.document.model.entity.DocumentEntity;
import io.lexiloom.application.document.repository.DocumentRepository;
import io.lexiloom.application.document.api.dto.DocumentStateEnum;
import io.lexiloom.application.document.model.event.DocumentStateTransitionEvent;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

@DisplayName("DocumentStateApiImpl 跨模块状态回滚契约测试")
class DocumentStateApiImplTest {

  private DocumentRepository documentRepository;
  private ApplicationEventPublisher eventPublisher;
  private DocumentStateApiImpl stateApi;

  @BeforeEach
  void setUp() {
    documentRepository = mock(DocumentRepository.class);
    eventPublisher = mock(ApplicationEventPublisher.class);
    stateApi = new DocumentStateApiImpl(documentRepository, eventPublisher);
  }

  @Test
  @DisplayName("文档存在且状态不同时，成功回滚为待审批并发布流转事件")
  void shouldRollbackToPendingApprovalAndPublishEvent() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(100L, 10L, "测试文档", "摘要", "正文", WorkSpaceAclEnum.INTERNAL);
    doc.transitionTo(DocumentStateEnum.APPROVED);

    when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

    stateApi.rollbackState(1L, DocumentStateEnum.PENDING_APPROVAL, "向量化失败");

    assertThat(doc.getStatus()).isEqualTo(DocumentStateEnum.PENDING_APPROVAL);
    verify(documentRepository).save(doc);

    ArgumentCaptor<DocumentStateTransitionEvent> eventCaptor =
        ArgumentCaptor.forClass(DocumentStateTransitionEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());

    DocumentStateTransitionEvent published = eventCaptor.getValue();
    assertThat(published.documentId()).isEqualTo(1L);
    assertThat(published.fromState()).isEqualTo(DocumentStateEnum.APPROVED);
    assertThat(published.toState()).isEqualTo(DocumentStateEnum.PENDING_APPROVAL);
    assertThat(published.comment()).isEqualTo("向量化失败");
  }

  @Test
  @DisplayName("文档不存在或状态已一致时，安全跳过且不发布事件")
  void shouldSkipWhenDocNotFoundOrStateMatches() {
    stateApi.rollbackState(null, DocumentStateEnum.PENDING_APPROVAL, "空");
    verify(documentRepository, never()).save(any());

    when(documentRepository.findById(2L)).thenReturn(Optional.empty());
    stateApi.rollbackState(2L, DocumentStateEnum.PENDING_APPROVAL, "不存在");
    verify(documentRepository, never()).save(any());

    DocumentEntity doc =
        DocumentEntity.createFromImport(100L, 10L, "测试文档", "摘要", "正文", WorkSpaceAclEnum.INTERNAL);
    when(documentRepository.findById(3L)).thenReturn(Optional.of(doc));
    // 初始状态即为 PENDING_APPROVAL
    stateApi.rollbackState(3L, DocumentStateEnum.PENDING_APPROVAL, "已一致");
    verify(documentRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
  }
}
