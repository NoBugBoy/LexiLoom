package io.lexiloom.application.document.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lexiloom.application.common.ErrorCode;
import io.lexiloom.application.common.storage.FileStorageGateway;
import io.lexiloom.application.document.acl.DocAccessControlService;
import io.lexiloom.application.document.model.entity.DocSourceFileEntity;
import io.lexiloom.application.document.model.entity.DocumentEntity;
import io.lexiloom.application.document.model.event.DocumentAsyncParseEvent;
import io.lexiloom.application.document.parser.DocParseStatusEnum;
import io.lexiloom.application.document.parser.DocParserGateway;
import io.lexiloom.application.document.repository.DocSourceFileRepository;
import io.lexiloom.application.document.repository.DocVersionRepository;
import io.lexiloom.application.document.repository.DocumentRepository;
import io.lexiloom.application.document.statemachine.DocumentStateEnum;
import io.lexiloom.application.document.statemachine.DocumentStateTransitionEvent;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
import io.lexiloom.application.exception.BusinessException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DocumentImportPipelineTest {

  @Mock private FileStorageGateway fileStorageGateway;
  @Mock private DocParserGateway docParserGateway;
  @Mock private DocSourceFileRepository docSourceFileRepository;
  @Mock private DocumentRepository documentRepository;
  @Mock private DocAccessControlService docAccessControlService;
  @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;

  @Mock private DocVersionRepository docVersionRepository;

  private static final Long OPERATOR_ID = 10L;

  private DocumentImportPipeline pipeline;

  @BeforeEach
  void setUp() {
    DocumentImportTransactionalService transactionalService =
        new DocumentImportTransactionalService(
            docSourceFileRepository, documentRepository, docVersionRepository, eventPublisher);
    pipeline =
        new DocumentImportPipeline(
            fileStorageGateway,
            docParserGateway,
            docSourceFileRepository,
            documentRepository,
            transactionalService,
            eventPublisher,
            docAccessControlService);
  }

  @Test
  @DisplayName("导入 Markdown 文件：成功上传 rustfs、解析正文并创建 DRAFT 文档")
  void testImportMarkdownSuccess() {
    // Arrange
    byte[] mdBytes = "# LexiLoom 架构指南\n\n这是系统架构说明。".getBytes(StandardCharsets.UTF_8);
    DocumentImportCommand command =
        DocumentImportCommand.ofBytes(1L, 10L, "architecture.md", mdBytes, "text/markdown");

    when(docSourceFileRepository.findFirstByWorkSpaceIdAndFileHashOrderByCreateTimeDesc(
            eq(1L), anyString()))
        .thenReturn(Optional.empty());
    when(fileStorageGateway.upload(anyString(), any(InputStream.class), anyString(), anyLong()))
        .thenReturn("http://rustfs.lexiloom.io/workspaces/1/docs/arch.md");

    when(docSourceFileRepository.save(any(DocSourceFileEntity.class)))
        .thenAnswer(
            invocation -> {
              DocSourceFileEntity entity = invocation.getArgument(0);
              if (entity.getId() == null) {
                ReflectionTestUtils.setField(entity, "id", 100L);
              }
              return entity;
            });

    when(docSourceFileRepository.findById(100L))
        .thenAnswer(
            inv -> {
              DocSourceFileEntity entity =
                  DocSourceFileEntity.create(
                      1L,
                      null,
                      "architecture.md",
                      (long) mdBytes.length,
                      "md",
                      "text/markdown",
                      "dummy-hash",
                      "path",
                      "url");
              ReflectionTestUtils.setField(entity, "id", 100L);
              return Optional.of(entity);
            });

    when(documentRepository.save(any(DocumentEntity.class)))
        .thenAnswer(
            inv -> {
              DocumentEntity doc = inv.getArgument(0);
              ReflectionTestUtils.setField(doc, "id", 200L);
              return doc;
            });

    // Act
    DocumentImportResult result = pipeline.importFile(command, OPERATOR_ID);

    // Assert
    assertThat(result.isSuccess()).isTrue();
    assertThat(result.documentId()).isEqualTo(200L);
    assertThat(result.sourceFileId()).isEqualTo(100L);
    assertThat(result.instantUpload()).isFalse();
    assertThat(result.parseStatus()).isEqualTo(DocParseStatusEnum.ASYNC_PROCESSING);

    verify(fileStorageGateway)
        .upload(
            anyString(), any(InputStream.class), eq("text/markdown"), eq((long) mdBytes.length));
    verify(eventPublisher).publishEvent(any(DocumentAsyncParseEvent.class));
  }

  @Test
  @DisplayName("导入 Word 文件：成功转换为 Markdown 格式并持久化")
  void testImportWordDocxSuccess() {
    // Arrange
    byte[] docxBytes = "dummy docx binary content".getBytes(StandardCharsets.UTF_8);
    DocumentImportCommand command =
        DocumentImportCommand.ofBytes(
            1L,
            20L,
            "项目规范.docx",
            docxBytes,
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    when(docSourceFileRepository.findFirstByWorkSpaceIdAndFileHashOrderByCreateTimeDesc(
            eq(1L), anyString()))
        .thenReturn(Optional.empty());
    when(fileStorageGateway.upload(anyString(), any(InputStream.class), anyString(), anyLong()))
        .thenReturn("http://rustfs.lexiloom.io/workspaces/1/docs/standard.docx");

    when(docSourceFileRepository.save(any(DocSourceFileEntity.class)))
        .thenAnswer(
            invocation -> {
              DocSourceFileEntity entity = invocation.getArgument(0);
              if (entity.getId() == null) {
                ReflectionTestUtils.setField(entity, "id", 101L);
              }
              return entity;
            });

    when(docSourceFileRepository.findById(101L))
        .thenAnswer(
            inv -> {
              DocSourceFileEntity entity =
                  DocSourceFileEntity.create(
                      1L,
                      null,
                      "项目规范.docx",
                      (long) docxBytes.length,
                      "docx",
                      "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                      "dummy-hash",
                      "path",
                      "url");
              ReflectionTestUtils.setField(entity, "id", 101L);
              return Optional.of(entity);
            });

    when(documentRepository.save(any(DocumentEntity.class)))
        .thenAnswer(
            inv -> {
              DocumentEntity doc = inv.getArgument(0);
              ReflectionTestUtils.setField(doc, "id", 201L);
              return doc;
            });

    // Act
    DocumentImportResult result = pipeline.importFile(command, OPERATOR_ID);

    // Assert
    assertThat(result.isSuccess()).isTrue();
    assertThat(result.documentId()).isEqualTo(201L);
    assertThat(result.parseStatus()).isEqualTo(DocParseStatusEnum.ASYNC_PROCESSING);
    verify(eventPublisher).publishEvent(any(DocumentAsyncParseEvent.class));
  }

  @Test
  @DisplayName("导入 PDF 文件：默认双轨制 (PREVIEW_ONLY) 固化版面原件预览模式")
  void testImportPdfDefaultPreviewMode() {
    // Arrange
    byte[] pdfBytes = "%PDF-1.4 dummy pdf content".getBytes(StandardCharsets.UTF_8);
    DocumentImportCommand command =
        DocumentImportCommand.ofBytes(1L, 30L, "深度学习导论.pdf", pdfBytes, "application/pdf");

    when(docSourceFileRepository.findFirstByWorkSpaceIdAndFileHashOrderByCreateTimeDesc(
            eq(1L), anyString()))
        .thenReturn(Optional.empty());
    when(fileStorageGateway.upload(anyString(), any(InputStream.class), anyString(), anyLong()))
        .thenReturn("http://rustfs.lexiloom.io/workspaces/1/docs/dl.pdf");

    when(docSourceFileRepository.save(any(DocSourceFileEntity.class)))
        .thenAnswer(
            invocation -> {
              DocSourceFileEntity entity = invocation.getArgument(0);
              if (entity.getId() == null) {
                ReflectionTestUtils.setField(entity, "id", 102L);
              }
              return entity;
            });

    when(docSourceFileRepository.findById(102L))
        .thenAnswer(
            inv -> {
              DocSourceFileEntity entity =
                  DocSourceFileEntity.create(
                      1L,
                      null,
                      "深度学习导论.pdf",
                      (long) pdfBytes.length,
                      "pdf",
                      "application/pdf",
                      "dummy-hash",
                      "path",
                      "url");
              ReflectionTestUtils.setField(entity, "id", 102L);
              return Optional.of(entity);
            });

    when(documentRepository.save(any(DocumentEntity.class)))
        .thenAnswer(
            inv -> {
              DocumentEntity doc = inv.getArgument(0);
              ReflectionTestUtils.setField(doc, "id", 202L);
              return doc;
            });

    // Act
    DocumentImportResult result = pipeline.importFile(command, OPERATOR_ID);

    // Assert
    assertThat(result.isSuccess()).isTrue();
    assertThat(result.parseStatus()).isEqualTo(DocParseStatusEnum.ASYNC_PROCESSING);
    assertThat(result.documentId()).isEqualTo(202L);
    verify(eventPublisher).publishEvent(any(DocumentAsyncParseEvent.class));
  }

  @Test
  @DisplayName("秒传去重命中：复用已有存储路径，无需重复上传 rustfs")
  void testImportInstantUploadDeduplication() {
    // Arrange
    byte[] content = "重复文件内容测试".getBytes(StandardCharsets.UTF_8);

    DocSourceFileEntity existing =
        DocSourceFileEntity.create(
            1L,
            null,
            "old.txt",
            (long) content.length,
            "txt",
            "text/plain",
            "dummy-sha256",
            "workspaces/1/docs/old.txt",
            "http://rustfs/old.txt");
    ReflectionTestUtils.setField(existing, "id", 999L);

    when(docSourceFileRepository.findFirstByWorkSpaceIdAndFileHashOrderByCreateTimeDesc(
            eq(1L), anyString()))
        .thenReturn(Optional.of(existing));

    when(docSourceFileRepository.save(any(DocSourceFileEntity.class)))
        .thenAnswer(
            invocation -> {
              DocSourceFileEntity entity = invocation.getArgument(0);
              if (entity.getId() == null) {
                ReflectionTestUtils.setField(entity, "id", 103L);
              }
              return entity;
            });

    when(docSourceFileRepository.findById(103L))
        .thenAnswer(
            inv -> {
              DocSourceFileEntity entity =
                  DocSourceFileEntity.create(
                      1L,
                      null,
                      "new_name.txt",
                      (long) content.length,
                      "txt",
                      "text/plain",
                      "dummy-sha256",
                      "workspaces/1/docs/old.txt",
                      "http://rustfs/old.txt");
              ReflectionTestUtils.setField(entity, "id", 103L);
              return Optional.of(entity);
            });

    when(documentRepository.save(any(DocumentEntity.class)))
        .thenAnswer(
            inv -> {
              DocumentEntity doc = inv.getArgument(0);
              ReflectionTestUtils.setField(doc, "id", 203L);
              return doc;
            });

    DocumentImportCommand command =
        DocumentImportCommand.ofBytes(1L, 0L, "new_name.txt", content, "text/plain");

    // Act
    DocumentImportResult result = pipeline.importFile(command, OPERATOR_ID);

    // Assert
    assertThat(result.instantUpload()).isTrue();
    assertThat(result.storagePath()).isEqualTo("workspaces/1/docs/old.txt");
    verify(fileStorageGateway, never())
        .upload(anyString(), any(InputStream.class), anyString(), anyLong());
  }

  @Test
  @DisplayName("异常流测试：对象存储上传失败应抛出 FILE_STORAGE_ERROR 业务异常")
  void testImportFileStorageUploadFails() {
    byte[] content = "test content".getBytes(StandardCharsets.UTF_8);
    DocumentImportCommand command =
        DocumentImportCommand.ofBytes(1L, 0L, "fail.txt", content, "text/plain");

    when(docSourceFileRepository.findFirstByWorkSpaceIdAndFileHashOrderByCreateTimeDesc(
            eq(1L), anyString()))
        .thenReturn(Optional.empty());
    when(fileStorageGateway.upload(anyString(), any(InputStream.class), anyString(), anyLong()))
        .thenThrow(new RuntimeException("Rustfs connection timeout"));

    assertThatThrownBy(() -> pipeline.importFile(command, OPERATOR_ID))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("文件存储失败");
  }

  @Test
  @DisplayName("重新解析重试：状态重置为 ASYNC_PROCESSING 并重新发布异步解析领域事件")
  void testRetryParse_resetsStatusAndDispatchesEvent() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 10L, "文档", null, "", 777L, WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 100L);
    doc.markParseFailed("前次解析超时");

    DocSourceFileEntity sourceFile =
        DocSourceFileEntity.create(
            1L, 100L, "test.pdf", 1024L, "pdf", "application/pdf", "hash", "path", "url");
    ReflectionTestUtils.setField(sourceFile, "id", 777L);

    pipeline.retryParse(doc, sourceFile, OPERATOR_ID);

    assertThat(doc.getParseStatus()).isEqualTo(DocParseStatusEnum.ASYNC_PROCESSING);
    assertThat(sourceFile.getParseStatus()).isEqualTo(DocParseStatusEnum.ASYNC_PROCESSING);
    verify(documentRepository).save(doc);
    verify(docSourceFileRepository).save(sourceFile);
    verify(eventPublisher).publishEvent(any(DocumentAsyncParseEvent.class));
  }

  @Test
  @DisplayName("异步解析流：解析器返回 ASYNC_PROCESSING 时，生成占位文档并发布异步解析事件")
  void testImportFileAsyncProcessingDispatchesEvent() {
    byte[] content = "pdf content".getBytes(StandardCharsets.UTF_8);
    DocumentImportCommand command =
        DocumentImportCommand.ofBytes(1L, 10L, "paper.pdf", content, "application/pdf");

    when(docSourceFileRepository.findFirstByWorkSpaceIdAndFileHashOrderByCreateTimeDesc(
            eq(1L), anyString()))
        .thenReturn(Optional.empty());
    when(fileStorageGateway.upload(anyString(), any(InputStream.class), anyString(), anyLong()))
        .thenReturn("http://rustfs/paper.pdf");

    when(docSourceFileRepository.save(any(DocSourceFileEntity.class)))
        .thenAnswer(
            invocation -> {
              DocSourceFileEntity entity = invocation.getArgument(0);
              if (entity.getId() == null) {
                ReflectionTestUtils.setField(entity, "id", 888L);
              }
              return entity;
            });

    when(docSourceFileRepository.findById(888L))
        .thenAnswer(
            inv -> {
              DocSourceFileEntity entity =
                  DocSourceFileEntity.create(
                      1L,
                      null,
                      "paper.pdf",
                      (long) content.length,
                      "pdf",
                      "application/pdf",
                      "hash",
                      "path",
                      "url");
              ReflectionTestUtils.setField(entity, "id", 888L);
              return Optional.of(entity);
            });

    when(documentRepository.save(any(DocumentEntity.class)))
        .thenAnswer(
            inv -> {
              DocumentEntity doc = inv.getArgument(0);
              ReflectionTestUtils.setField(doc, "id", 999L);
              return doc;
            });

    DocumentImportResult result = pipeline.importFile(command, OPERATOR_ID);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.isAsyncProcessing()).isTrue();
    assertThat(result.parseStatus()).isEqualTo(DocParseStatusEnum.ASYNC_PROCESSING);
    assertThat(result.documentId()).isEqualTo(999L);

    verify(eventPublisher).publishEvent(any(DocumentAsyncParseEvent.class));
  }

  @Test
  @DisplayName("安全：客户端文件名中的目录穿越片段被剥离，不会污染对象存储键")
  void testImportSanitizesTraversalFileName() {
    byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
    DocumentImportCommand command =
        DocumentImportCommand.ofBytes(1L, 0L, "../../../etc/passwd.md", content, "text/markdown");

    when(docSourceFileRepository.findFirstByWorkSpaceIdAndFileHashOrderByCreateTimeDesc(
            eq(1L), anyString()))
        .thenReturn(Optional.empty());
    when(fileStorageGateway.upload(anyString(), any(InputStream.class), anyString(), anyLong()))
        .thenThrow(new RuntimeException("stop-after-key-capture"));

    assertThatThrownBy(() -> pipeline.importFile(command, OPERATOR_ID))
        .isInstanceOf(BusinessException.class);

    verify(fileStorageGateway)
        .upload(
            argThat(key -> key.startsWith("workspaces/1/docs/") && !key.contains("..")),
            any(InputStream.class),
            anyString(),
            anyLong());
  }

  @Test
  @DisplayName("安全：导入前校验目标工作空间的写权限")
  void testImportAssertsWorkspaceWriteAccess() {
    byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
    DocumentImportCommand command =
        DocumentImportCommand.ofBytes(9L, 0L, "a.md", content, "text/markdown");

    org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.Document.DOC_ACCESS_DENIED, "无权导入"))
        .when(docAccessControlService)
        .assertCanWriteWorkSpace(9L, OPERATOR_ID);

    assertThatThrownBy(() -> pipeline.importFile(command, OPERATOR_ID))
        .isInstanceOf(BusinessException.class);

    verify(fileStorageGateway, never())
        .upload(anyString(), any(InputStream.class), anyString(), anyLong());
  }

  @Test
  @DisplayName("多次导入同名文件且哈希一致：命中秒传，不递增版本")
  void testImportSameFileWithSameHash_ReturnsVersionUnchangedWithoutIncrement() {
    byte[] content = "# Hello World".getBytes(StandardCharsets.UTF_8);
    DocumentImportCommand command =
        DocumentImportCommand.ofBytes(1L, 0L, "guide.md", content, "text/markdown");
    String computedHash = DocumentImportPipeline.calculateSha256(content);

    DocSourceFileEntity existingSourceFile =
        DocSourceFileEntity.create(
            1L,
            500L,
            "guide.md",
            (long) content.length,
            "md",
            "text/markdown",
            computedHash,
            "workspaces/1/docs/guide.md",
            "http://rustfs/workspaces/1/docs/guide.md");
    ReflectionTestUtils.setField(existingSourceFile, "id", 200L);

    DocumentEntity existingDoc =
        DocumentEntity.createFromImport(
            1L,
            0L,
            "guide",
            "摘要",
            "# Hello World",
            200L,
            "guide.md",
            WorkSpaceAclEnum.INTERNAL,
            DocParseStatusEnum.SUCCESS);
    ReflectionTestUtils.setField(existingDoc, "id", 500L);

    when(docSourceFileRepository.findFirstByWorkSpaceIdAndOriginalFileNameOrderByCreateTimeDesc(
            eq(1L), eq("guide.md")))
        .thenReturn(Optional.of(existingSourceFile));
    when(documentRepository.findByIdAndWorkSpaceId(500L, 1L)).thenReturn(Optional.of(existingDoc));
    when(docSourceFileRepository.findById(200L)).thenReturn(Optional.of(existingSourceFile));

    // Act
    DocumentImportResult result = pipeline.importFile(command, OPERATOR_ID);

    // Assert
    assertThat(result.isSuccess()).isTrue();
    assertThat(result.versionUnchanged()).isTrue();
    assertThat(result.instantUpload()).isTrue();
    assertThat(result.documentId()).isEqualTo(500L);
    verify(fileStorageGateway, never()).upload(anyString(), any(), anyString(), anyLong());
    verify(documentRepository, never()).save(any());
  }

  @Test
  @DisplayName("多次导入同名文件且哈希不同：自增版本号为 v2.0，保存快照且原发布状态回归未审批")
  void testImportSameFileWithDifferentHash_IncrementsVersionAndSavesNewSnapshot() {
    byte[] oldContent = "# Old Content".getBytes(StandardCharsets.UTF_8);
    byte[] newContent = "# New Updated Content".getBytes(StandardCharsets.UTF_8);
    String oldHash = DocumentImportPipeline.calculateSha256(oldContent);
    String newHash = DocumentImportPipeline.calculateSha256(newContent);

    DocSourceFileEntity oldSourceFile =
        DocSourceFileEntity.create(
            1L,
            500L,
            "guide.md",
            (long) oldContent.length,
            "md",
            "text/markdown",
            oldHash,
            "workspaces/1/docs/guide_old.md",
            "http://rustfs/workspaces/1/docs/guide_old.md");
    ReflectionTestUtils.setField(oldSourceFile, "id", 200L);

    DocumentEntity existingDoc =
        DocumentEntity.createFromImport(
            1L,
            0L,
            "guide",
            "摘要",
            "# Old Content",
            200L,
            "guide.md",
            WorkSpaceAclEnum.INTERNAL,
            DocParseStatusEnum.SUCCESS);
    ReflectionTestUtils.setField(existingDoc, "id", 500L);
    existingDoc.publish("v1.0"); // Published version 1

    DocumentImportCommand command =
        DocumentImportCommand.ofBytes(1L, 0L, "guide.md", newContent, "text/markdown");

    when(docSourceFileRepository.findFirstByWorkSpaceIdAndOriginalFileNameOrderByCreateTimeDesc(
            eq(1L), eq("guide.md")))
        .thenReturn(Optional.of(oldSourceFile));
    when(documentRepository.findByIdAndWorkSpaceId(500L, 1L)).thenReturn(Optional.of(existingDoc));
    when(docSourceFileRepository.findById(200L)).thenReturn(Optional.of(oldSourceFile));

    // Upload new content
    when(docSourceFileRepository.findFirstByWorkSpaceIdAndFileHashOrderByCreateTimeDesc(
            eq(1L), eq(newHash)))
        .thenReturn(Optional.empty());
    when(fileStorageGateway.upload(anyString(), any(InputStream.class), anyString(), anyLong()))
        .thenReturn("http://rustfs/workspaces/1/docs/new.md");

    DocSourceFileEntity newSourceFile =
        DocSourceFileEntity.create(
            1L,
            null,
            "guide.md",
            (long) newContent.length,
            "md",
            "text/markdown",
            newHash,
            "workspaces/1/docs/new.md",
            "http://rustfs/workspaces/1/docs/new.md");
    ReflectionTestUtils.setField(newSourceFile, "id", 201L);

    when(docSourceFileRepository.save(any(DocSourceFileEntity.class))).thenReturn(newSourceFile);
    when(docSourceFileRepository.findById(201L)).thenReturn(Optional.of(newSourceFile));

    when(documentRepository.findById(500L)).thenReturn(Optional.of(existingDoc));
    when(documentRepository.save(any(DocumentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

    // Act
    DocumentImportResult result = pipeline.importFile(command, OPERATOR_ID);

    // Assert
    assertThat(result.isSuccess()).isTrue();
    assertThat(result.versionUnchanged()).isFalse();
    assertThat(existingDoc.getCurrentVersion()).isEqualTo(2);
    assertThat(existingDoc.getCurrentVersionTag()).isEqualTo("v2.0");
    assertThat(existingDoc.getStatus()).isEqualTo(DocumentStateEnum.PENDING_APPROVAL);
    assertThat(existingDoc.getPublishedVersion()).isNull();

    // Verify state transition event published to trigger ES cleanup
    org.mockito.ArgumentCaptor<DocumentStateTransitionEvent> eventCaptor =
        org.mockito.ArgumentCaptor.forClass(DocumentStateTransitionEvent.class);
    verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().documentId()).isEqualTo(500L);
    assertThat(eventCaptor.getValue().toState()).isEqualTo(DocumentStateEnum.PENDING_APPROVAL);

    verify(eventPublisher, org.mockito.Mockito.atLeastOnce())
        .publishEvent(any(DocumentAsyncParseEvent.class));
  }
}
