package com.knowflow.application.document.api.dto;

import com.knowflow.application.document.statemachine.DocumentStateEnum;
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
    LocalDateTime createTime) {

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
