package io.lexiloom.application.document.model.event;

import io.lexiloom.application.document.parser.DocParserEngineEnum;

/** 异步文档解析调度领域事件 (不可变 Java record) 触发后台工作线程执行远程结果轮询与 Markdown 提炼 */
public record DocumentAsyncParseEvent(
    Long documentId,
    Long sourceFileId,
    String batchId,
    String taskId,
    DocParserEngineEnum engine,
    String fileName,
    Long workSpaceId,
    Long operatorId) {

  public DocumentAsyncParseEvent(
      Long documentId,
      Long sourceFileId,
      String batchId,
      String taskId,
      DocParserEngineEnum engine,
      String fileName,
      Long workSpaceId) {
    this(documentId, sourceFileId, batchId, taskId, engine, fileName, workSpaceId, null);
  }
}
