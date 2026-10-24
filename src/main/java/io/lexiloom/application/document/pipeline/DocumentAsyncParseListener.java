package io.lexiloom.application.document.pipeline;

import io.lexiloom.application.document.model.entity.DocSourceFileEntity;
import io.lexiloom.application.document.model.event.DocumentAsyncParseEvent;
import io.lexiloom.application.document.parser.DocParserEngineEnum;
import io.lexiloom.application.document.queue.DocParseQueueService;
import io.lexiloom.application.document.queue.DocParseTaskItem;
import io.lexiloom.application.document.repository.DocSourceFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 统一异步文档解析后台事件监听器
 *
 * <p>监听文档导入或重试触发的解析事件，封装任务载荷并投递到 Redis 分布式排队与单并发调度服务中。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentAsyncParseListener {

  private final DocSourceFileRepository docSourceFileRepository;
  private final DocParseQueueService docParseQueueService;

  @Async
  @EventListener
  public void onAsyncParseDispatched(DocumentAsyncParseEvent event) {
    if (event == null || event.documentId() == null) {
      return;
    }

    log.info(
        "【DocumentAsyncParseListener】接收到文档解析事件，投递至 Redis 排队服务: docId={}, sourceFileId={}, engine={}, file={}",
        event.documentId(),
        event.sourceFileId(),
        event.engine(),
        event.fileName());

    DocSourceFileEntity sourceFile =
        event.sourceFileId() != null
            ? docSourceFileRepository.findById(event.sourceFileId()).orElse(null)
            : null;

    DocParseTaskItem task =
        new DocParseTaskItem(
            event.documentId(),
            event.sourceFileId(),
            event.workSpaceId(),
            event.operatorId(),
            event.engine() != null ? event.engine() : DocParserEngineEnum.AUTO,
            event.fileName(),
            sourceFile != null ? sourceFile.getStoragePath() : null,
            sourceFile != null ? sourceFile.getMimeType() : null,
            System.currentTimeMillis());

    docParseQueueService.enqueue(task);
  }
}
