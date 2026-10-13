package com.knowflow.application.document.api.dto;

import com.knowflow.application.document.model.enums.DocSourceTypeEnum;
import com.knowflow.application.document.parser.DocParseStatusEnum;
import com.knowflow.application.document.statemachine.DocumentStateEnum;
import com.knowflow.application.enums.WorkSpaceAclEnum;
import java.time.LocalDateTime;

/** 文档核心数据传输对象 (不可变 record) */
public record DocumentDTO(
    Long id,
    Long workSpaceId,
    Long categoryId,
    String title,
    String summary,
    String content,
    DocumentStateEnum status,
    DocParseStatusEnum parseStatus,
    WorkSpaceAclEnum visibility,
    DocSourceTypeEnum sourceType,
    Long sourceFileId,
    String fileName,
    Integer currentVersion,
    String currentVersionTag,
    Integer versionCount,
    Integer publishedVersion,
    String coverUrl,
    Integer wordCount,
    Integer readCount,
    Integer likeCount,
    Long createBy,
    LocalDateTime createTime,
    LocalDateTime updateTime) {

  /** 兼容旧版 20 个参数构造器（无 fileName, versionCount, publishedVersion） */
  public DocumentDTO(
      Long id,
      Long workSpaceId,
      Long categoryId,
      String title,
      String summary,
      String content,
      DocumentStateEnum status,
      DocParseStatusEnum parseStatus,
      WorkSpaceAclEnum visibility,
      DocSourceTypeEnum sourceType,
      Long sourceFileId,
      Integer currentVersion,
      String currentVersionTag,
      String coverUrl,
      Integer wordCount,
      Integer readCount,
      Integer likeCount,
      Long createBy,
      LocalDateTime createTime,
      LocalDateTime updateTime) {
    this(
        id,
        workSpaceId,
        categoryId,
        title,
        summary,
        content,
        status,
        parseStatus,
        visibility,
        sourceType,
        sourceFileId,
        title,
        currentVersion,
        currentVersionTag,
        (currentVersion != null && currentVersion > 0) ? currentVersion : 1,
        (status == DocumentStateEnum.APPROVED) ? currentVersion : null,
        coverUrl,
        wordCount,
        readCount,
        likeCount,
        createBy,
        createTime,
        updateTime);
  }

  /** 兼容旧版 19 个参数构造器，默认 parseStatus 为 SUCCESS */
  public DocumentDTO(
      Long id,
      Long workSpaceId,
      Long categoryId,
      String title,
      String summary,
      String content,
      DocumentStateEnum status,
      WorkSpaceAclEnum visibility,
      DocSourceTypeEnum sourceType,
      Long sourceFileId,
      Integer currentVersion,
      String currentVersionTag,
      String coverUrl,
      Integer wordCount,
      Integer readCount,
      Integer likeCount,
      Long createBy,
      LocalDateTime createTime,
      LocalDateTime updateTime) {
    this(
        id,
        workSpaceId,
        categoryId,
        title,
        summary,
        content,
        status,
        DocParseStatusEnum.SUCCESS,
        visibility,
        sourceType,
        sourceFileId,
        currentVersion,
        currentVersionTag,
        coverUrl,
        wordCount,
        readCount,
        likeCount,
        createBy,
        createTime,
        updateTime);
  }
}
