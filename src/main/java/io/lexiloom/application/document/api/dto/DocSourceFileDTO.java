package io.lexiloom.application.document.api.dto;

import cn.hutool.core.date.DatePattern;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.lexiloom.application.document.parser.DocParseStatusEnum;
import io.lexiloom.application.document.parser.DocParserEngineEnum;
import java.time.LocalDateTime;

/** 来源原始文件资产数据传输对象 (不可变 record) 仅暴露安全公开信息与访问链接，隔离内部存储架构路径细节 */
public record DocSourceFileDTO(
    Long id,
    Long workSpaceId,
    Long documentId,
    String originalFileName,
    Long fileSize,
    String fileExt,
    String mimeType,
    String fileHash,
    String storageUrl,
    DocParseStatusEnum parseStatus,
    DocParserEngineEnum parseEngine,
    String rawText,
    String parseMetadata,
    String errorMessage,
    @JsonFormat(pattern = DatePattern.NORM_DATETIME_PATTERN) LocalDateTime createTime) {

  /** 兼容旧版 14 个参数构造器（无 parseMetadata） */
  public DocSourceFileDTO(
      Long id,
      Long workSpaceId,
      Long documentId,
      String originalFileName,
      Long fileSize,
      String fileExt,
      String mimeType,
      String fileHash,
      String storageUrl,
      DocParseStatusEnum parseStatus,
      DocParserEngineEnum parseEngine,
      String rawText,
      String errorMessage,
      LocalDateTime createTime) {
    this(
        id,
        workSpaceId,
        documentId,
        originalFileName,
        fileSize,
        fileExt,
        mimeType,
        fileHash,
        storageUrl,
        parseStatus,
        parseEngine,
        rawText,
        null,
        errorMessage,
        createTime);
  }
}
