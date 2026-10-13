package com.knowflow.application.document.pipeline;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowflow.application.document.model.entity.DocSourceFileEntity;
import com.knowflow.application.document.model.event.DocumentAsyncParseEvent;
import com.knowflow.application.document.model.event.DocumentUpdatedEvent;
import com.knowflow.application.document.parser.DocParseCommand;
import com.knowflow.application.document.parser.DocParseResult;
import com.knowflow.application.document.parser.DocParserEngineEnum;
import com.knowflow.application.document.parser.DocParserGateway;
import com.knowflow.application.document.repository.DocSourceFileRepository;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class DocumentAsyncParseListenerTest {

  private DocParserGateway docParserGateway;
  private DocSourceFileRepository docSourceFileRepository;
  private DocumentImportTransactionalService transactionalService;
  private ApplicationEventPublisher eventPublisher;
  private DocumentAsyncParseListener listener;

  @BeforeEach
  void setUp() {
    docParserGateway = mock(DocParserGateway.class);
    docSourceFileRepository = mock(DocSourceFileRepository.class);
    transactionalService = mock(DocumentImportTransactionalService.class);
    eventPublisher = mock(ApplicationEventPublisher.class);
    listener =
        new DocumentAsyncParseListener(
            docParserGateway, docSourceFileRepository, transactionalService, eventPublisher);
  }

  @Test
  @DisplayName("DeepDoc 异步解析任务处理成功：后台调用网关 -> 事务提交写库 -> 发布 DocumentUpdatedEvent 同步 ES 索引")
  void testOnAsyncParseDispatched_DeepDocSuccess() {
    DocumentAsyncParseEvent event =
        new DocumentAsyncParseEvent(
            101L, 201L, null, null, DocParserEngineEnum.DEEPDOC, "manual.docx", 1L, 10L);

    DocSourceFileEntity sourceFile =
        DocSourceFileEntity.create(
            1L, 101L, "manual.docx", 2048L, "docx", "application/docx", "hash", "path", "url");
    when(docSourceFileRepository.findById(201L)).thenReturn(Optional.of(sourceFile));

    DocParseResult parseResult =
        DocParseResult.success(
            "# 操作手册\n\n内容", "操作手册 内容", "操作手册", DocParserEngineEnum.DEEPDOC, Map.of());
    when(docParserGateway.parse(any(DocParseCommand.class))).thenReturn(parseResult);

    listener.onAsyncParseDispatched(event);

    verify(transactionalService)
        .completeAsyncParse(
            101L, 201L, "# 操作手册\n\n内容", "操作手册 内容", "操作手册", DocParserEngineEnum.DEEPDOC, Map.of());
    verify(eventPublisher).publishEvent(any(DocumentUpdatedEvent.class));
  }

  @Test
  @DisplayName("预览模式异步解析：写入固化预览说明 -> 事务提交 -> 发布 DocumentUpdatedEvent")
  void testOnAsyncParseDispatched_PreviewOnly() {
    DocumentAsyncParseEvent event =
        new DocumentAsyncParseEvent(
            103L, 203L, null, null, DocParserEngineEnum.DEEPDOC, "doc.pdf", 1L, 10L);

    DocSourceFileEntity sourceFile =
        DocSourceFileEntity.create(
            1L, 103L, "doc.pdf", 4096L, "pdf", "application/pdf", "hash", "path", "url");
    when(docSourceFileRepository.findById(203L)).thenReturn(Optional.of(sourceFile));

    DocParseResult previewResult =
        DocParseResult.previewOnly("PDF 文本", "文档标题", DocParserEngineEnum.DEEPDOC, Map.of());
    when(docParserGateway.parse(any(DocParseCommand.class))).thenReturn(previewResult);

    listener.onAsyncParseDispatched(event);

    verify(transactionalService)
        .completeAsyncParse(
            eq(103L),
            eq(203L),
            org.mockito.ArgumentMatchers.nullable(String.class),
            eq("PDF 文本"),
            eq("文档标题"),
            eq(DocParserEngineEnum.DEEPDOC),
            eq(Map.of()));
    verify(eventPublisher).publishEvent(any(DocumentUpdatedEvent.class));
  }

  @Test
  @DisplayName("异步解析任务处理失败：发生异常时优雅降级并记录 failAsyncParse，不发布 ES 更新事件")
  void testOnAsyncParseDispatched_Failure() {
    DocumentAsyncParseEvent event =
        new DocumentAsyncParseEvent(
            102L, 202L, null, null, DocParserEngineEnum.DEEPDOC, "error.pdf", 1L, 10L);

    DocSourceFileEntity sourceFile =
        DocSourceFileEntity.create(
            1L, 102L, "error.pdf", 1024L, "pdf", "application/pdf", "hash", "path", "url");
    when(docSourceFileRepository.findById(202L)).thenReturn(Optional.of(sourceFile));

    when(docParserGateway.parse(any(DocParseCommand.class)))
        .thenThrow(new RuntimeException("DeepDoc 远程调用失败"));

    listener.onAsyncParseDispatched(event);

    verify(transactionalService).failAsyncParse(eq(102L), eq(202L), eq("DeepDoc 远程调用失败"));
    verify(eventPublisher, never()).publishEvent(any(DocumentUpdatedEvent.class));
  }

  @Test
  @DisplayName("边界值：event 或 documentId 为空时直接忽略")
  void testOnAsyncParseDispatched_NullEventOrDocId() {
    listener.onAsyncParseDispatched(null);
    listener.onAsyncParseDispatched(
        new DocumentAsyncParseEvent(null, null, null, null, null, null, null, null));

    verify(transactionalService, never())
        .completeAsyncParse(any(), any(), any(), any(), any(), any(), any());
  }
}
