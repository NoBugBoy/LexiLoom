package io.lexiloom.application.document.api.dto;

import cn.hutool.core.date.DatePattern;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.lexiloom.application.document.api.dto.DocumentStateEnum;
import java.time.LocalDateTime;

/** 文档历史版本快照数据传输对象 (不可变 record) */
public record DocVersionDTO(
    Long id,
    Long workSpaceId,
    Long documentId,
    Integer versionNumber,
    String versionTag,
    String title,
    String content,
    String changeSummary,
    Long publisherId,
    Integer wordCount,
    DocumentStateEnum status,
    @JsonFormat(pattern = DatePattern.NORM_DATETIME_PATTERN) LocalDateTime createTime) {

  /** 兼容不带 status 的旧构造方法，默认状态为 PENDING_APPROVAL */
  public DocVersionDTO(
      Long id,
      Long workSpaceId,
      Long documentId,
      Integer versionNumber,
      String versionTag,
      String title,
      String content,
      String changeSummary,
      Long publisherId,
      Integer wordCount,
      LocalDateTime createTime) {
    this(
        id,
        workSpaceId,
        documentId,
        versionNumber,
        versionTag,
        title,
        content,
        changeSummary,
        publisherId,
        wordCount,
        DocumentStateEnum.PENDING_APPROVAL,
        createTime);
  }
}
