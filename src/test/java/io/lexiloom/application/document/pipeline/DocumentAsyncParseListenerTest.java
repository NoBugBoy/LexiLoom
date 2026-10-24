package io.lexiloom.application.document.pipeline;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lexiloom.application.document.model.entity.DocSourceFileEntity;
import io.lexiloom.application.document.model.event.DocumentAsyncParseEvent;
import io.lexiloom.application.document.parser.DocParserEngineEnum;
import io.lexiloom.application.document.queue.DocParseQueueService;
import io.lexiloom.application.document.queue.DocParseTaskItem;
import io.lexiloom.application.document.repository.DocSourceFileRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocumentAsyncParseListenerTest {

  private DocSourceFileRepository docSourceFileRepository;
  private DocParseQueueService docParseQueueService;
  private DocumentAsyncParseListener listener;

  @BeforeEach
  void setUp() {
    docSourceFileRepository = mock(DocSourceFileRepository.class);
    docParseQueueService = mock(DocParseQueueService.class);
    listener = new DocumentAsyncParseListener(docSourceFileRepository, docParseQueueService);
  }

  @Test
  @DisplayName("接收到异步解析领域事件，成功封装载荷并投递到 Redis 排队服务")
  void testOnAsyncParseDispatched_EnqueuesTask() {
    DocumentAsyncParseEvent event =
        new DocumentAsyncParseEvent(
            101L, 201L, null, null, DocParserEngineEnum.DEEPDOC, "manual.docx", 1L, 10L);

    DocSourceFileEntity sourceFile =
        DocSourceFileEntity.create(
            1L, 101L, "manual.docx", 2048L, "docx", "application/docx", "hash", "path/to/file", "url");
    when(docSourceFileRepository.findById(201L)).thenReturn(Optional.of(sourceFile));

    listener.onAsyncParseDispatched(event);

    verify(docParseQueueService).enqueue(any(DocParseTaskItem.class));
  }

  @Test
  @DisplayName("边界值：event 或 documentId 为空时直接忽略，不入队")
  void testOnAsyncParseDispatched_NullEventOrDocId() {
    listener.onAsyncParseDispatched(null);
    listener.onAsyncParseDispatched(
        new DocumentAsyncParseEvent(null, null, null, null, null, null, null, null));

    verify(docParseQueueService, never()).enqueue(any());
  }
}
