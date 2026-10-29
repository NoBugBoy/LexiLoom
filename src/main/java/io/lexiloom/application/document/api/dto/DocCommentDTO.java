package io.lexiloom.application.document.api.dto;

import cn.hutool.core.date.DatePattern;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;

/** 文档协同评论数据传输对象 (不可变 record) */
public record DocCommentDTO(
    Long id,
    Long workSpaceId,
    Long documentId,
    Long userId,
    Long parentId,
    Long replyToUserId,
    String content,
    Integer likeCount,
    @JsonFormat(pattern = DatePattern.NORM_DATETIME_PATTERN) LocalDateTime createTime) {}
