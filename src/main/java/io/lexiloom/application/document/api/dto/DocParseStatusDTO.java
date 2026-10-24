package io.lexiloom.application.document.api.dto;

import io.lexiloom.application.document.parser.DocParseStatusEnum;
import org.jspecify.annotations.Nullable;

/** 文档解析状态轻量级数据传输对象 (供前端短轮询) */
public record DocParseStatusDTO(
    Long documentId,
    Long sourceFileId,
    DocParseStatusEnum parseStatus,
    String errorMessage,
    @Nullable Integer queuePosition) {

  public DocParseStatusDTO(
      Long documentId, Long sourceFileId, DocParseStatusEnum parseStatus, String errorMessage) {
    this(documentId, sourceFileId, parseStatus, errorMessage, null);
  }
}
