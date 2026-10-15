package io.lexiloom.application.document.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lexiloom.application.document.model.entity.DocumentEntity;
import io.lexiloom.application.document.model.event.DocumentUpdatedEvent;
import io.lexiloom.application.document.model.request.DeepDocParseCallbackRequest;
import io.lexiloom.application.document.parser.DocParserEngineEnum;
import io.lexiloom.application.document.parser.engine.DeepDocDocParserEngine;
import io.lexiloom.application.document.pipeline.DocumentImportTransactionalService;
import io.lexiloom.application.document.repository.DocumentRepository;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
import io.lexiloom.application.exception.BusinessException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

@DisplayName("DocParseCallbackService DeepDoc 异步解析回调处理测试")
class DocParseCallbackServiceTest {

  private DocumentRepository documentRepository;
  private DocumentImportTransactionalService transactionalService;
  private DeepDocDocParserEngine deepDocParserEngine;
  private ApplicationEventPublisher eventPublisher;
  private DocParseCallbackService service;

  @BeforeEach
  void setUp() {
    documentRepository = mock(DocumentRepository.class);
    transactionalService = mock(DocumentImportTransactionalService.class);
    deepDocParserEngine = mock(DeepDocDocParserEngine.class);
    eventPublisher = mock(ApplicationEventPublisher.class);
    service =
        new DocParseCallbackService(
            documentRepository, transactionalService, deepDocParserEngine, eventPublisher);
  }

  @Test
  @DisplayName("请求为 null 或 documentId 为空时抛出非法参数异常")
  void testHandleCallback_InvalidRequest() {
    assertThatThrownBy(() -> service.handleCallback(null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("documentId 不能为空");

    DeepDocParseCallbackRequest requestNoDocId =
        new DeepDocParseCallbackRequest(
            200,
            "SUCCESS",
            "task1",
            null,
            10L,
            1L,
            1L,
            "test.pdf",
            "pdf",
            "md",
            "md",
            2,
            List.of(),
            List.of(),
            null,
            null);
    assertThatThrownBy(() -> service.handleCallback(requestNoDocId))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("文档不存在时安全退出，不抛出异常也不调用落库")
  void testHandleCallback_DocNotFound() {
    when(documentRepository.findById(999L)).thenReturn(Optional.empty());

    DeepDocParseCallbackRequest request =
        new DeepDocParseCallbackRequest(
            200,
            "SUCCESS",
            "task1",
            999L,
            10L,
            1L,
            1L,
            "test.pdf",
            "pdf",
            "md",
            "md",
            2,
            List.of(),
            List.of(),
            null,
            null);

    service.handleCallback(request);

    verify(transactionalService, never())
        .completeAsyncParse(any(), any(), any(), any(), any(), any(), any());
    verify(transactionalService, never()).failAsyncParse(any(), any(), any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  @DisplayName("收到解析失败回调时：调用 failAsyncParse，不触发检索更新事件")
  void testHandleCallback_FailedStatus() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(
            1L, 10L, "文档标题", "摘要", "占位内容", 50L, "file.pdf", WorkSpaceAclEnum.INTERNAL, null);
    when(documentRepository.findById(100L)).thenReturn(Optional.of(doc));

    DeepDocParseCallbackRequest request =
        new DeepDocParseCallbackRequest(
            500,
            "FAILED",
            "task1",
            100L,
            50L,
            1L,
            1L,
            "file.pdf",
            "pdf",
            null,
            null,
            0,
            List.of(),
            List.of(),
            "PDF 格式损坏无法解析",
            "解析失败");

    service.handleCallback(request);

    verify(transactionalService).failAsyncParse(100L, 50L, "PDF 格式损坏无法解析");
    verify(transactionalService, never())
        .completeAsyncParse(any(), any(), any(), any(), any(), any(), any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  @DisplayName("收到解析成功回调时：转存插图 -> 事务落库 -> 发布 DocumentUpdatedEvent 同步索引与向量")
  void testHandleCallback_Success() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(
            1L, 10L, "文档标题", "摘要", "占位内容", 50L, "file.pdf", WorkSpaceAclEnum.INTERNAL, null);
    when(documentRepository.findById(100L)).thenReturn(Optional.of(doc));

    String rawMarkdown =
        "# 详细设计书\n\n"
            + "正文内容\n\n"
            + "<figure data-type=\"image_ocr\"><img src=\"/static/figures/fig1.png\" /></figure>";
    String transferredMarkdown =
        "# 详细设计书\n\n"
            + "正文内容\n\n"
            + "<figure data-type=\"image_ocr\"><img src=\"http://rustfs/lexiloom/figures/fig1.png\""
            + " /></figure>";
    when(deepDocParserEngine.transferFiguresToStorage(anyString())).thenReturn(transferredMarkdown);

    DeepDocParseCallbackRequest request =
        new DeepDocParseCallbackRequest(
            200,
            "SUCCESS",
            "task_abc_123",
            100L,
            50L,
            1L,
            2L,
            "file.pdf",
            "pdf",
            rawMarkdown,
            rawMarkdown,
            rawMarkdown.length(),
            List.of(Map.of("index", 0, "text", "详细设计书")),
            List.of(),
            null,
            null);

    service.handleCallback(request);

    verify(deepDocParserEngine).transferFiguresToStorage(rawMarkdown);
    verify(transactionalService)
        .completeAsyncParse(
            eq(100L),
            eq(50L),
            eq(transferredMarkdown),
            anyString(),
            eq("详细设计书"),
            eq(DocParserEngineEnum.DEEPDOC),
            any());
    verify(eventPublisher).publishEvent(any(DocumentUpdatedEvent.class));
  }
}
