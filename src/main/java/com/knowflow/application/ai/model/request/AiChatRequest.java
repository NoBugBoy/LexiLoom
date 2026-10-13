package com.knowflow.application.ai.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** AI 对话请求参数 (不可变 record) */
public record AiChatRequest(
    @NotBlank(message = "提问内容不能为空") String message,
    @NotNull(message = "工作空间ID不能为空") Long workspaceId,
    String sessionId,
    Boolean enableThinking,
    Long documentId) {

  public AiChatRequest(String message, Long workspaceId, String sessionId, Boolean enableThinking) {
    this(message, workspaceId, sessionId, enableThinking, null);
  }

  public boolean isThinkingEnabled() {
    return Boolean.TRUE.equals(enableThinking);
  }
}
