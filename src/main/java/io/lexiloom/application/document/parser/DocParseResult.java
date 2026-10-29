package io.lexiloom.application.document.parser;

import java.util.Collections;
import java.util.Map;

/** 文档解析标准输出结果（不可变值对象） */
public record DocParseResult(
    String markdownContent,
    String rawText,
    String suggestedTitle,
    DocParseStatusEnum status,
    DocParserEngineEnum engineUsed,
    Map<String, Object> metadata,
    String errorMessage) {

  public DocParseResult {
    metadata = (metadata == null) ? Collections.emptyMap() : Collections.unmodifiableMap(metadata);
  }

  public static DocParseResult success(
      String markdownContent,
      String rawText,
      String suggestedTitle,
      DocParserEngineEnum engineUsed,
      Map<String, Object> metadata) {
    return new DocParseResult(
        markdownContent,
        rawText,
        suggestedTitle,
        DocParseStatusEnum.SUCCESS,
        engineUsed,
        metadata,
        null);
  }

  public static DocParseResult failure(DocParserEngineEnum engineUsed, String errorMessage) {
    return new DocParseResult(
        null,
        null,
        null,
        DocParseStatusEnum.FAILED,
        engineUsed,
        Collections.emptyMap(),
        errorMessage);
  }

  public static DocParseResult queued(DocParserEngineEnum engineUsed, Map<String, Object> metadata) {
    java.util.Map<String, Object> meta =
        new java.util.HashMap<>(metadata != null ? metadata : Collections.emptyMap());
    return new DocParseResult(
        null, null, null, DocParseStatusEnum.QUEUED, engineUsed, meta, null);
  }

  public static DocParseResult asyncProcessing(
      DocParserEngineEnum engineUsed, String batchId, String taskId, Map<String, Object> metadata) {
    java.util.Map<String, Object> meta =
        new java.util.HashMap<>(metadata != null ? metadata : Collections.emptyMap());
    if (batchId != null) {
      meta.put("batchId", batchId);
    }
    if (taskId != null) {
      meta.put("taskId", taskId);
    }
    return new DocParseResult(
        null, null, null, DocParseStatusEnum.ASYNC_PROCESSING, engineUsed, meta, null);
  }

  public boolean isSuccess() {
    return this.status == DocParseStatusEnum.SUCCESS;
  }

  public boolean isFailure() {
    return this.status == DocParseStatusEnum.FAILED;
  }

  public boolean isAsyncProcessing() {
    return this.status == DocParseStatusEnum.ASYNC_PROCESSING;
  }

  public boolean isQueued() {
    return this.status == DocParseStatusEnum.QUEUED;
  }
}
