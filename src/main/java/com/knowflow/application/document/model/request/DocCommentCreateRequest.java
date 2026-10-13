package com.knowflow.application.document.model.request;

import jakarta.validation.constraints.NotBlank;

/** 创建评论请求对象 (不可变 record) */
public record DocCommentCreateRequest(
    Long parentId, Long replyToUserId, @NotBlank(message = "评论内容不能为空") String content) {}
