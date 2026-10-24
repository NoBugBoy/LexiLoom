package io.lexiloom.application.ai.model.request;

import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.Nullable;

/**
 * OpenAPI 大模型对话与知识问答请求体 (Chat Completion Request)
 */
public record OpenApiChatCompletionRequest(
    @NotBlank(message = "提问内容 message 不能为空") String message,
    @Nullable Long workspaceId,
    @Nullable String sessionId,
    @Nullable Boolean stream,
    @Nullable Boolean enableThinking,
    @Nullable Long documentId,
    @Nullable Integer topK) {

  public boolean isStream() {
    return Boolean.TRUE.equals(stream);
  }
}
