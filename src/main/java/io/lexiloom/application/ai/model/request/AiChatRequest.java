package io.lexiloom.application.ai.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Collections;
import java.util.Map;

/**
 * AI 对话请求参数 (不可变 record)
 *
 * @param nativeHybridSearch 是否启用 ES 原生单请求双路混合检索。{@code true} 时由 ES 服务端线性融合打分， 配额隔离（vectorTopK /
 *     lexicalTopK 保底）将失效；{@code null} 时跟随全局配置 {@code lexiloom.ai.rag.native-hybrid-search}。
 */
public record AiChatRequest(
    @NotBlank(message = "提问内容不能为空") String message,
    @NotNull(message = "工作空间ID不能为空") Long workspaceId,
    String sessionId,
    Boolean enableThinking,
    Long documentId,
    Integer topK,
    Map<Long, Integer> weights,
    Boolean nativeHybridSearch) {

  public AiChatRequest {
    weights = weights != null ? Map.copyOf(weights) : Collections.emptyMap();
  }

  public AiChatRequest(String message, Long workspaceId, String sessionId, Boolean enableThinking) {
    this(message, workspaceId, sessionId, enableThinking, null, null, Collections.emptyMap(), null);
  }

  public AiChatRequest(
      String message, Long workspaceId, String sessionId, Boolean enableThinking, Long documentId) {
    this(
        message,
        workspaceId,
        sessionId,
        enableThinking,
        documentId,
        null,
        Collections.emptyMap(),
        null);
  }

  public AiChatRequest(
      String message,
      Long workspaceId,
      String sessionId,
      Boolean enableThinking,
      Long documentId,
      Integer topK,
      Map<Long, Integer> weights) {
    this(message, workspaceId, sessionId, enableThinking, documentId, topK, weights, null);
  }

  public boolean isThinkingEnabled() {
    return Boolean.TRUE.equals(enableThinking);
  }
}
