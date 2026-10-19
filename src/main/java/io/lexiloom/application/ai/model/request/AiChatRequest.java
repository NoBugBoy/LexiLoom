package io.lexiloom.application.ai.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Collections;
import java.util.Map;

/** AI 对话请求参数 (不可变 record) */
public record AiChatRequest(
    @NotBlank(message = "提问内容不能为空") String message,
    @NotNull(message = "工作空间ID不能为空") Long workspaceId,
    String sessionId,
    Boolean enableThinking,
    Long documentId,
    Integer topK,
    Map<Long, Integer> weights) {

  public AiChatRequest {
    weights = weights != null ? Map.copyOf(weights) : Collections.emptyMap();
  }

  public AiChatRequest(String message, Long workspaceId, String sessionId, Boolean enableThinking) {
    this(message, workspaceId, sessionId, enableThinking, null, null, Collections.emptyMap());
  }

  public AiChatRequest(
      String message, Long workspaceId, String sessionId, Boolean enableThinking, Long documentId) {
    this(message, workspaceId, sessionId, enableThinking, documentId, null, Collections.emptyMap());
  }

  public boolean isThinkingEnabled() {
    return Boolean.TRUE.equals(enableThinking);
  }
}
