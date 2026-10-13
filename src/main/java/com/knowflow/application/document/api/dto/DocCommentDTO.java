package com.knowflow.application.document.api.dto;

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
    LocalDateTime createTime) {}
