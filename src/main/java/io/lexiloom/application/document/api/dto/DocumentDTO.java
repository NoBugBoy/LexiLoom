package io.lexiloom.application.document.api.dto;

import cn.hutool.core.date.DatePattern;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.lexiloom.application.document.model.enums.DocSourceTypeEnum;
import io.lexiloom.application.document.parser.DocParseStatusEnum;
import io.lexiloom.application.document.api.dto.DocumentStateEnum;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
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
    Long createBy,
    @JsonFormat(pattern = DatePattern.NORM_DATETIME_PATTERN) LocalDateTime createTime,
    @JsonFormat(pattern = DatePattern.NORM_DATETIME_PATTERN) LocalDateTime updateTime) {

  /** 派生并替换用于原件预览的正文内容与真实来源文件名 */
  public DocumentDTO withSourcePreview(String effectiveContent, String realFileName) {
    return new DocumentDTO(
        id,
        workSpaceId,
        categoryId,
        title,
        summary,
        effectiveContent,
        status,
        parseStatus,
        visibility,
        sourceType,
        sourceFileId,
        realFileName,
        currentVersion,
        currentVersionTag,
        versionCount,
        publishedVersion,
        coverUrl,
        wordCount,
        readCount,
        createBy,
        createTime,
        updateTime);
  }

  /** 兼容旧版参数构造器（无 fileName, versionCount, publishedVersion） */
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
        createBy,
        createTime,
        updateTime);
  }

  /** 兼容旧版参数构造器，默认 parseStatus 为 SUCCESS */
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
        createBy,
        createTime,
        updateTime);
  }
}
