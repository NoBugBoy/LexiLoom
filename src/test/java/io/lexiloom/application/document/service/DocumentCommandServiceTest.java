package io.lexiloom.application.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lexiloom.application.document.acl.DocAccessControlService;
import io.lexiloom.application.document.api.dto.DocumentDTO;
import io.lexiloom.application.document.mapper.DocumentMapper;
import io.lexiloom.application.document.model.entity.DocSourceFileEntity;
import io.lexiloom.application.document.model.entity.DocumentEntity;
import io.lexiloom.application.document.model.enums.DocSourceTypeEnum;
import io.lexiloom.application.document.model.event.DocumentDeletedEvent;
import io.lexiloom.application.document.model.event.DocumentUpdatedEvent;
import io.lexiloom.application.document.model.request.DocumentTransitionRequest;
import io.lexiloom.application.document.parser.DocParserEngineEnum;
import io.lexiloom.application.document.pipeline.DocumentImportCommand;
import io.lexiloom.application.document.pipeline.DocumentImportPipeline;
import io.lexiloom.application.document.repository.DocCategoryRepository;
import io.lexiloom.application.document.repository.DocCommentRepository;
import io.lexiloom.application.document.repository.DocSourceFileRepository;
import io.lexiloom.application.document.repository.DocumentRepository;
import io.lexiloom.application.document.api.dto.DocumentEventEnum;
import io.lexiloom.application.document.statemachine.DocumentStateContext;
import io.lexiloom.application.document.api.dto.DocumentStateEnum;
import io.lexiloom.application.document.statemachine.DocumentStateMachineEngine;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
import io.lexiloom.application.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DocumentCommandServiceTest {

  @Mock private DocumentRepository documentRepository;
  @Mock private DocAccessControlService docAccessControlService;
  @Mock private DocumentStateMachineEngine stateMachineEngine;
  @Mock private DocumentImportPipeline importPipeline;
  @Mock private DocumentMapper documentMapper;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private DocCommentRepository docCommentRepository;

  @Mock private DocCategoryRepository docCategoryRepository;

  @Mock private DocSourceFileRepository docSourceFileRepository;
  @Mock private io.lexiloom.application.approval.api.ApprovalApi approvalApi;

  private DocumentCommandService commandService;

  @BeforeEach
  void setUp() {
    commandService =
        new DocumentCommandService(
            documentRepository,
            docAccessControlService,
            stateMachineEngine,
            importPipeline,
            documentMapper,
            eventPublisher,
            docCommentRepository,
            docCategoryRepository,
            docSourceFileRepository,
            approvalApi);
  }

  @Test
  @DisplayName("状态机流转：重新提交审批并触发状态转移")
  void testTransitionStateSuccess() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 0L, "文档", "摘要", "内容", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 100L);
    ReflectionTestUtils.setField(doc, "status", DocumentStateEnum.REJECTED);
    when(documentRepository.findById(100L)).thenReturn(Optional.of(doc));

    when(stateMachineEngine.fire(
            eq(DocumentStateEnum.REJECTED),
            eq(DocumentEventEnum.SUBMIT),
            any(DocumentStateContext.class)))
        .thenReturn(DocumentStateEnum.PENDING_APPROVAL);

    when(documentRepository.save(any(DocumentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

    DocumentDTO mockDto =
        new DocumentDTO(
            100L,
            1L,
            0L,
            "文档",
            "摘要",
            "内容",
            DocumentStateEnum.PENDING_APPROVAL,
            WorkSpaceAclEnum.INTERNAL,
            DocSourceTypeEnum.IMPORT,
            null,
            0,
            "v0.1-draft",
            null,
            2,
            0,
            10L,
            LocalDateTime.now(),
            LocalDateTime.now());
    when(documentMapper.toDTO(any(DocumentEntity.class))).thenReturn(mockDto);

    DocumentTransitionRequest req =
        new DocumentTransitionRequest(DocumentEventEnum.SUBMIT, "请求领导审批");
    DocumentDTO result = commandService.transitionState(100L, req, 10L);

    assertThat(result.status()).isEqualTo(DocumentStateEnum.PENDING_APPROVAL);
    verify(docAccessControlService).assertCanRead(doc, 10L);
    verify(stateMachineEngine)
        .fire(
            eq(DocumentStateEnum.REJECTED),
            eq(DocumentEventEnum.SUBMIT),
            any(DocumentStateContext.class));
  }

  @Test
  @DisplayName("审批通过转移：转为 APPROVED 时状态机负责流转与副作用")
  void testTransitionToPublishedSuccess() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 0L, "文档", "摘要", "内容", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 100L);
    doc.transitionTo(DocumentStateEnum.PENDING_APPROVAL);

    when(documentRepository.findById(100L)).thenReturn(Optional.of(doc));
    when(stateMachineEngine.fire(
            eq(DocumentStateEnum.PENDING_APPROVAL),
            eq(DocumentEventEnum.APPROVE),
            any(DocumentStateContext.class)))
        .thenReturn(DocumentStateEnum.APPROVED);

    when(documentRepository.save(any(DocumentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

    DocumentTransitionRequest req =
        new DocumentTransitionRequest(DocumentEventEnum.APPROVE, "审批通过准予发布");
    commandService.transitionState(100L, req, 1L);

    verify(stateMachineEngine)
        .fire(
            eq(DocumentStateEnum.PENDING_APPROVAL),
            eq(DocumentEventEnum.APPROVE),
            any(DocumentStateContext.class));
    verify(documentRepository).save(doc);
  }

  @Test
  @DisplayName("删除文档：校验权限并删除，级联软删除评论，并发布 DocumentDeletedEvent")
  void testDeleteDocumentSuccess() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 0L, "即将删除文档", "摘要", "内容", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 100L);
    when(documentRepository.findById(100L)).thenReturn(Optional.of(doc));

    commandService.delete(100L, 10L);

    verify(docAccessControlService).assertCanWrite(doc, 10L);
    verify(documentRepository).delete(doc);
    verify(docCommentRepository).softDeleteByDocumentId(100L);
    verify(eventPublisher).publishEvent(any(DocumentDeletedEvent.class));
  }

  @Test
  @DisplayName("删除文档：导入生成的文档级联软删除关联的来源文件记录")
  void testDeleteDocumentWithSourceFileCascade() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 0L, "导入文档", "摘要", "内容", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 101L);
    ReflectionTestUtils.setField(doc, "sourceFileId", 555L);
    when(documentRepository.findById(101L)).thenReturn(Optional.of(doc));

    DocSourceFileEntity sourceFile =
        DocSourceFileEntity.create(
            1L, 101L, "file.docx", 1024L, "docx", "application/docx", "hash", "path", "url");
    when(docSourceFileRepository.findById(555L)).thenReturn(Optional.of(sourceFile));

    commandService.delete(101L, 10L);

    verify(docSourceFileRepository).delete(sourceFile);
    verify(documentRepository).delete(doc);
  }

  @Test
  @DisplayName("重试解析：对解析失败的文档重新触发导入管线解析")
  void testRetryParseSuccess() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 0L, "失败文档", "摘要", "内容", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 102L);
    ReflectionTestUtils.setField(doc, "sourceFileId", 666L);
    when(documentRepository.findById(102L)).thenReturn(Optional.of(doc));

    DocSourceFileEntity sourceFile =
        DocSourceFileEntity.create(
            1L, 102L, "failed.docx", 2048L, "docx", "application/docx", "hash2", "path2", "url2");
    when(docSourceFileRepository.findById(666L)).thenReturn(Optional.of(sourceFile));

    commandService.retryParse(102L, 10L);

    verify(docAccessControlService).assertCanWrite(doc, 10L);
    verify(importPipeline).retryParse(doc, sourceFile, 10L);
  }

  @Test
  @DisplayName("重试解析：对非导入文档抛出业务异常")
  void testRetryParseNonImportedDocFails() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 0L, "手动文档", "摘要", "内容", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 103L);
    ReflectionTestUtils.setField(doc, "sourceFileId", null);
    when(documentRepository.findById(103L)).thenReturn(Optional.of(doc));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> commandService.retryParse(103L, 10L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("该文档非外部文件导入生成");
  }

  @Test
  @DisplayName("文件导入：超过 100MB 上限直接拒绝，不读取文件内容")
  void testImportFileRejectsOversizedFile() {
    org.springframework.web.multipart.MultipartFile file =
        org.mockito.Mockito.mock(org.springframework.web.multipart.MultipartFile.class);
    when(file.isEmpty()).thenReturn(false);
    when(file.getSize()).thenReturn(101L * 1024 * 1024);

    assertThatThrownBy(
            () -> commandService.importFile(1L, 0L, file, null, WorkSpaceAclEnum.INTERNAL, 10L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("上传文件过大");

    verify(importPipeline, never()).importFile(any(DocumentImportCommand.class), anyLong());
  }

  @Test
  @DisplayName("文件导入：文件名净化后透传操作人 ID，由管线执行工作空间写权限校验")
  void testImportFileSanitizesNameAndPassesOperator() throws Exception {
    org.springframework.web.multipart.MultipartFile file =
        org.mockito.Mockito.mock(org.springframework.web.multipart.MultipartFile.class);
    when(file.isEmpty()).thenReturn(false);
    when(file.getSize()).thenReturn(16L);
    when(file.getOriginalFilename()).thenReturn("../..\\evil.md");
    when(file.getContentType()).thenReturn("text/markdown");
    when(file.getInputStream())
        .thenAnswer(
            inv ->
                new java.io.ByteArrayInputStream(
                    "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

    commandService.importFile(1L, 0L, file, null, WorkSpaceAclEnum.INTERNAL, 10L);

    org.mockito.ArgumentCaptor<DocumentImportCommand> captor =
        org.mockito.ArgumentCaptor.forClass(DocumentImportCommand.class);
    verify(importPipeline).importFile(captor.capture(), eq(10L));
    assertThat(captor.getValue().originalFileName()).isEqualTo("evil.md");
  }

  @Test
  @DisplayName("文件导入：50MB 文件（处于 100MB 阈值内）正常放行，不被误拦截")
  void testImportFileAllowsFileWithin100MbLimit() throws Exception {
    org.springframework.web.multipart.MultipartFile file =
        org.mockito.Mockito.mock(org.springframework.web.multipart.MultipartFile.class);
    when(file.isEmpty()).thenReturn(false);
    when(file.getSize()).thenReturn(50L * 1024 * 1024);
    when(file.getOriginalFilename()).thenReturn("large-handbook.pdf");
    when(file.getContentType()).thenReturn("application/pdf");
    when(file.getInputStream())
        .thenAnswer(
            inv ->
                new java.io.ByteArrayInputStream(
                    "pdf".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

    commandService.importFile(1L, 0L, file, null, WorkSpaceAclEnum.INTERNAL, 10L);

    verify(importPipeline).importFile(any(DocumentImportCommand.class), eq(10L));
  }
}
