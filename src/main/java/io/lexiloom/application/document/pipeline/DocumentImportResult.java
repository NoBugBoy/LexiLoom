package io.lexiloom.application.document.pipeline;

import io.lexiloom.application.document.parser.DocParseStatusEnum;
import io.lexiloom.application.document.parser.DocParserEngineEnum;
import java.util.Collections;
import java.util.Map;

/** 文档导入执行结果（不可变 Java Record） */
public record DocumentImportResult(
    Long documentId,
    Long sourceFileId,
    String title,
    String originalFileName,
    String storagePath,
    String storageUrl,
    DocParseStatusEnum parseStatus,
    DocParserEngineEnum engineUsed,
    boolean instantUpload,
    String previewOrMarkdownContent,
    Map<String, Object> metadata) {

  public DocumentImportResult {
    metadata = (metadata == null) ? Collections.emptyMap() : Collections.unmodifiableMap(metadata);
  }

  public static DocumentImportResult success(
      Long documentId,
      Long sourceFileId,
      String title,
      String originalFileName,
      String storagePath,
      String storageUrl,
      DocParserEngineEnum engineUsed,
      boolean instantUpload,
      String markdownContent,
      Map<String, Object> metadata) {
    return new DocumentImportResult(
        documentId,
        sourceFileId,
        title,
        originalFileName,
        storagePath,
        storageUrl,
        DocParseStatusEnum.SUCCESS,
        engineUsed,
        instantUpload,
        markdownContent,
        metadata);
  }

  public static DocumentImportResult previewOnly(
      Long documentId,
      Long sourceFileId,
      String title,
      String originalFileName,
      String storagePath,
      String storageUrl,
      DocParserEngineEnum engineUsed,
      boolean instantUpload,
      String previewPlaceholder,
      Map<String, Object> metadata) {
    return new DocumentImportResult(
        documentId,
        sourceFileId,
        title,
        originalFileName,
        storagePath,
        storageUrl,
        DocParseStatusEnum.PREVIEW_ONLY,
        engineUsed,
        instantUpload,
        previewPlaceholder,
        metadata);
  }

  public static DocumentImportResult failure(
      Long sourceFileId,
      String originalFileName,
      String storagePath,
      String storageUrl,
      DocParserEngineEnum engineUsed,
      String errorMessage) {
    return new DocumentImportResult(
        null,
        sourceFileId,
        null,
        originalFileName,
        storagePath,
        storageUrl,
        DocParseStatusEnum.FAILED,
        engineUsed,
        false,
        errorMessage,
        Map.of("error", errorMessage != null ? errorMessage : ""));
  }

  public static DocumentImportResult asyncProcessing(
      Long documentId,
      Long sourceFileId,
      String title,
      String originalFileName,
      String storagePath,
      String storageUrl,
      DocParserEngineEnum engineUsed,
      boolean instantUpload,
      String placeholderContent,
      Map<String, Object> metadata) {
    return new DocumentImportResult(
        documentId,
        sourceFileId,
        title,
        originalFileName,
        storagePath,
        storageUrl,
        DocParseStatusEnum.ASYNC_PROCESSING,
        engineUsed,
        instantUpload,
        placeholderContent,
        metadata);
  }

  public boolean isSuccess() {
    return this.parseStatus == DocParseStatusEnum.SUCCESS
        || this.parseStatus == DocParseStatusEnum.PREVIEW_ONLY
        || this.parseStatus == DocParseStatusEnum.ASYNC_PROCESSING;
  }

  public boolean isAsyncProcessing() {
    return this.parseStatus == DocParseStatusEnum.ASYNC_PROCESSING;
  }

  public String suggestedTitle() {
    return this.title;
  }

  public String markdownContent() {
    return this.previewOrMarkdownContent;
  }

  public String errorMessage() {
    Object err = this.metadata.get("error");
    return err != null ? err.toString() : null;
  }

  public boolean versionUnchanged() {
    return Boolean.TRUE.equals(this.metadata.get("versionUnchanged"));
  }
}
