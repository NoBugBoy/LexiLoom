package io.lexiloom.application.document.search;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lexiloom.application.document.mapper.DocSearchMapper;
import io.lexiloom.application.document.model.entity.DocSourceFileEntity;
import io.lexiloom.application.document.model.entity.DocTagEntity;
import io.lexiloom.application.document.model.entity.DocTagRelationEntity;
import io.lexiloom.application.document.model.entity.DocumentEntity;
import io.lexiloom.application.document.model.event.DocumentDeletedEvent;
import io.lexiloom.application.document.model.event.DocumentUpdatedEvent;
import io.lexiloom.application.document.parser.DocParserEngineEnum;
import io.lexiloom.application.document.repository.DocSourceFileRepository;
import io.lexiloom.application.document.repository.DocTagRelationRepository;
import io.lexiloom.application.document.repository.DocTagRepository;
import io.lexiloom.application.document.repository.DocumentRepository;
import io.lexiloom.application.document.statemachine.DocumentEventEnum;
import io.lexiloom.application.document.statemachine.DocumentStateEnum;
import io.lexiloom.application.document.statemachine.DocumentStateTransitionEvent;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentSearchSyncListenerTest {

  @Mock private DocumentRepository documentRepository;
  @Mock private DocSourceFileRepository docSourceFileRepository;
  @Mock private DocTagRelationRepository docTagRelationRepository;
  @Mock private DocTagRepository docTagRepository;
  @Mock private DocSearchRepository docSearchRepository;
  @Mock private DocSearchMapper docSearchMapper;

  @InjectMocks private DocumentSearchSyncListener listener;

  @Test
  @DisplayName("APPROVED 事件：文档审批通过时应组装原文件纯文本与标签并保存至 ES 索引")
  void shouldIndexDocumentOnApprovedEvent() {
    Long docId = 101L;
    Long workspaceId = 1L;
    DocumentEntity doc =
        DocumentEntity.createFromImport(
            workspaceId, 2L, "Spring 实战", "摘要", "# 正文内容", WorkSpaceAclEnum.PUBLIC);
    doc.setId(docId);
    doc.transitionTo(DocumentStateEnum.APPROVED);

    DocSourceFileEntity sourceFile =
        DocSourceFileEntity.create(
            workspaceId,
            docId,
            "spring.docx",
            1024L,
            "docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "hash_123456",
            "storage/path/spring.docx",
            "http://rustfs/spring.docx");
    sourceFile.markParseSuccess("从 docx 提取的纯文本", DocParserEngineEnum.DEEPDOC);

    DocTagRelationEntity relation = DocTagRelationEntity.create(docId, 301L);
    DocTagEntity tag = DocTagEntity.create(workspaceId, "后端", "#FFFFFF");

    DocSearchDocument searchDoc =
        DocSearchDocument.builder().id("101").workspaceId("1").title("Spring 实战").build();

    when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
    when(docSourceFileRepository.findByDocumentId(docId)).thenReturn(Optional.of(sourceFile));
    when(docTagRelationRepository.findByDocumentId(docId)).thenReturn(List.of(relation));
    when(docTagRepository.findAllById(List.of(301L))).thenReturn(List.of(tag));
    when(docSearchMapper.toSearchDoc(doc, sourceFile, List.of("后端"))).thenReturn(searchDoc);

    DocumentStateTransitionEvent event =
        new DocumentStateTransitionEvent(
            docId,
            DocumentStateEnum.PENDING_APPROVAL,
            DocumentStateEnum.APPROVED,
            DocumentEventEnum.APPROVE,
            10L,
            "通过审批",
            LocalDateTime.now());

    listener.onDocumentStateTransition(event);

    verify(docSearchRepository).save(searchDoc);
  }

  @Test
  @DisplayName("REJECTED 事件：文档审批驳回时应从 ES 索引中安全移除")
  void shouldRemoveFromIndexOnRejectedEvent() {
    Long docId = 102L;
    DocumentStateTransitionEvent event =
        new DocumentStateTransitionEvent(
            docId,
            DocumentStateEnum.APPROVED,
            DocumentStateEnum.REJECTED,
            DocumentEventEnum.REJECT,
            10L,
            "审批驳回",
            LocalDateTime.now());

    listener.onDocumentStateTransition(event);

    verify(docSearchRepository).deleteById("102");
    verify(docSearchRepository, never()).save(any());
  }

  @Test
  @DisplayName("PENDING_APPROVAL 事件：文档重回待审核时应从 ES 索引中删除")
  void shouldRemoveFromIndexOnPendingApprovalEvent() {
    Long docId = 103L;
    DocumentStateTransitionEvent event =
        new DocumentStateTransitionEvent(
            docId,
            DocumentStateEnum.APPROVED,
            DocumentStateEnum.PENDING_APPROVAL,
            DocumentEventEnum.SUBMIT,
            10L,
            "重新提交审核",
            LocalDateTime.now());

    listener.onDocumentStateTransition(event);

    verify(docSearchRepository).deleteById("103");
    verify(docSearchRepository, never()).save(any());
  }

  @Test
  @DisplayName("APPROVED 事件但文档未在数据库中找到：不应触发 ES 写入")
  void shouldNotIndexWhenDocNotFound() {
    Long docId = 999L;
    when(documentRepository.findById(docId)).thenReturn(Optional.empty());

    DocumentStateTransitionEvent event =
        new DocumentStateTransitionEvent(
            docId,
            DocumentStateEnum.PENDING_APPROVAL,
            DocumentStateEnum.APPROVED,
            DocumentEventEnum.APPROVE,
            10L,
            "通过审批",
            LocalDateTime.now());

    listener.onDocumentStateTransition(event);

    verify(docSearchRepository, never()).save(any());
  }

  @Test
  @DisplayName("APPROVED 事件但文档状态并非 APPROVED（并发不一致保护）：不应触发 ES 写入")
  void shouldNotIndexWhenDocStatusIsNotApproved() {
    Long docId = 105L;
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 0L, "测试", "摘要", "内容", WorkSpaceAclEnum.PUBLIC);
    doc.setId(docId);
    // 状态仍为 PENDING_APPROVAL
    when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

    DocumentStateTransitionEvent event =
        new DocumentStateTransitionEvent(
            docId,
            DocumentStateEnum.PENDING_APPROVAL,
            DocumentStateEnum.APPROVED,
            DocumentEventEnum.APPROVE,
            10L,
            "通过审批",
            LocalDateTime.now());

    listener.onDocumentStateTransition(event);

    verify(docSearchRepository, never()).save(any());
  }

  @Test
  @DisplayName("ES 保存异常时应被捕获并不影响流程")
  void shouldHandleEsSaveExceptionGracefully() {
    Long docId = 106L;
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 0L, "标题", "摘要", "内容", WorkSpaceAclEnum.PUBLIC);
    doc.setId(docId);
    doc.transitionTo(DocumentStateEnum.APPROVED);

    when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
    when(docSourceFileRepository.findByDocumentId(docId)).thenReturn(Optional.empty());
    when(docTagRelationRepository.findByDocumentId(docId)).thenReturn(List.of());
    when(docSearchMapper.toSearchDoc(eq(doc), any(), any()))
        .thenReturn(DocSearchDocument.builder().id("106").build());
    doThrow(new RuntimeException("ES unavailable")).when(docSearchRepository).save(any());

    DocumentStateTransitionEvent event =
        new DocumentStateTransitionEvent(
            docId,
            DocumentStateEnum.PENDING_APPROVAL,
            DocumentStateEnum.APPROVED,
            DocumentEventEnum.APPROVE,
            10L,
            "通过审批",
            LocalDateTime.now());

    // 不应抛出异常
    listener.onDocumentStateTransition(event);

    verify(docSearchRepository).save(any());
  }

  @Test
  @DisplayName("DocumentDeletedEvent 事件：文档被删除时应从 ES 索引中彻底移除")
  void shouldRemoveFromIndexOnDocumentDeletedEvent() {
    Long docId = 107L;
    DocumentDeletedEvent event = new DocumentDeletedEvent(docId, 1L, 10L);

    listener.onDocumentDeleted(event);

    verify(docSearchRepository).deleteById("107");
    verify(docSearchRepository, never()).save(any());
  }

  @Test
  @DisplayName("DocumentUpdatedEvent 事件：审批通过文档内容更新时应刷新 ES 索引")
  void shouldRefreshIndexOnDocumentUpdatedEvent() {
    Long docId = 108L;
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 0L, "更新后的标题", "摘要", "新正文", WorkSpaceAclEnum.PUBLIC);
    doc.setId(docId);
    doc.transitionTo(DocumentStateEnum.APPROVED);

    when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
    when(docSourceFileRepository.findByDocumentId(docId)).thenReturn(Optional.empty());
    when(docTagRelationRepository.findByDocumentId(docId)).thenReturn(List.of());
    when(docSearchMapper.toSearchDoc(eq(doc), any(), any()))
        .thenReturn(DocSearchDocument.builder().id("108").build());

    DocumentUpdatedEvent event = new DocumentUpdatedEvent(docId, 1L, 10L);

    listener.onDocumentUpdated(event);

    verify(docSearchRepository).save(any());
    verify(docSearchRepository, never()).deleteById(any());
  }
}
