package com.knowflow.application.ai.model.request;

import jakarta.validation.constraints.NotNull;

/**
 * 创建新对话会话请求参数 (不可变 record)
 *
 * @param workspaceId 所属工作空间 ID
 * @param title 自定义会话标题 (可选，若为空则在首轮提问时自动生成)
 */
public record ConversationSessionCreateRequest(
    @NotNull(message = "工作空间ID不能为空") Long workspaceId, String title) {}
