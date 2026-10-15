package io.lexiloom.application.document.api.dto;

import io.lexiloom.application.document.parser.DocParseStatusEnum;

/** 文档解析状态轻量级数据传输对象 (供前端短轮询) */
public record DocParseStatusDTO(
    Long documentId, Long sourceFileId, DocParseStatusEnum parseStatus, String errorMessage) {}
