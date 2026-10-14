package com.knowflow.application.document.pipeline;

import com.knowflow.application.document.model.entity.DocSourceFileEntity;
import com.knowflow.application.document.model.event.DocumentAsyncParseEvent;
import com.knowflow.application.document.model.event.DocumentUpdatedEvent;
import com.knowflow.application.document.parser.DocParseCommand;
import com.knowflow.application.document.parser.DocParseResult;
import com.knowflow.application.document.parser.DocParserEngineEnum;
import com.knowflow.application.document.parser.DocParserGateway;
import com.knowflow.application.document.repository.DocSourceFileRepository;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** 统一异步文档解析后台事件监听器 (Worker) 在独立的后台工作线程池中执行长耗时的 DeepDoc 解析与远程调用，完全释放 Tomcat Web 线程。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentAsyncParseListener {

  private final DocParserGateway docParserGateway;
  private final DocSourceFileRepository docSourceFileRepository;
  private final DocumentImportTransactionalService transactionalService;
  private final ApplicationEventPublisher eventPublisher;

  @Async
  @EventListener
  public void onAsyncParseDispatched(DocumentAsyncParseEvent event) {
    if (event == null || event.documentId() == null) {
      return;
    }

    log.info(
        "【DocumentAsyncParseListener】后台工作线程接收到统一异步解析任务: docId={}, sourceFileId={}, engine={},"
            + " file={}",
        event.documentId(),
        event.sourceFileId(),
        event.engine(),
        event.fileName());

    try {
      DocSourceFileEntity sourceFile =
          docSourceFileRepository.findById(event.sourceFileId()).orElse(null);
      if (sourceFile == null) {
        log.warn("【DocumentAsyncParseListener】来源文件记录丢失: sourceFileId={}", event.sourceFileId());
        transactionalService.failAsyncParse(event.documentId(), event.sourceFileId(), "来源文件记录丢失");
        return;
      }

      // 1. 构造后台解析命令
      Map<String, Object> extraOptions = new HashMap<>();
      if ("pdf".equalsIgnoreCase(sourceFile.getFileExt())) {
        extraOptions.put("ocr", true);
      }
      if (event.documentId() != null) {
        extraOptions.put("documentId", event.documentId());
      }
      if (event.sourceFileId() != null) {
        extraOptions.put("sourceFileId", event.sourceFileId());
      }
      if (event.workSpaceId() != null) {
        extraOptions.put("workspaceId", event.workSpaceId());
      }
      if (event.operatorId() != null) {
        extraOptions.put("operatorId", event.operatorId());
      }

      DocParseCommand parseCmd =
          DocParseCommand.of(
              null,
              sourceFile.getStoragePath(),
              sourceFile.getOriginalFileName(),
              sourceFile.getMimeType(),
              event.engine() != null ? event.engine() : DocParserEngineEnum.AUTO,
              extraOptions);

      // 2. 调用统一解析网关（后台线程执行：DeepDoc HTTP POST）
      DocParseResult parseResult = docParserGateway.parse(parseCmd);

      if (parseResult.isAsyncProcessing()) {
        log.info(
            "【DocumentAsyncParseListener】解析任务已提交至异步解析引擎，等待 HTTP 回调通知: docId={}, sourceFileId={}, engine={}",
            event.documentId(),
            event.sourceFileId(),
            parseResult.engineUsed());
        return;
      }

      if (parseResult.isSuccess() || parseResult.isPreviewOnly()) {
        // 针对非回调引擎或同步返回解析结果（向后兼容）
        transactionalService.completeAsyncParse(
            event.documentId(),
            event.sourceFileId(),
            parseResult.markdownContent(),
            parseResult.rawText(),
            parseResult.suggestedTitle(),
            parseResult.engineUsed(),
            parseResult.metadata());
      } else {
        transactionalService.failAsyncParse(
            event.documentId(), event.sourceFileId(), parseResult.errorMessage());
        return;
      }

      // 3. 发布文档内容更新事件，联动更新 ES 检索索引
      eventPublisher.publishEvent(
          new DocumentUpdatedEvent(event.documentId(), event.workSpaceId(), event.operatorId()));

      log.info(
          "【DocumentAsyncParseListener】文档异步解析并落库成功: docId={}, engine={}",
          event.documentId(),
          parseResult.engineUsed());
    } catch (Exception e) {
      log.error(
          "【DocumentAsyncParseListener】文档异步解析失败: docId={}, sourceFileId={}, error={}",
          event.documentId(),
          event.sourceFileId(),
          e.getMessage(),
          e);
      transactionalService.failAsyncParse(event.documentId(), event.sourceFileId(), e.getMessage());
    }
  }
}
