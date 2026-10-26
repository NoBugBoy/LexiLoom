package io.lexiloom.application.ai.gateway.llm;

import java.util.List;
import java.util.Map;

/** LLM 对话执行命令对象 (不可变 record) */
public record LlmChatCommand(
    String prompt,
    String systemPrompt,
    List<ChatMessage> history,
    boolean enableThinking,
    Double temperature,
    Integer maxTokens) {

  public record ChatMessage(
      String role, String content, String toolCallId, List<ToolCall> toolCalls) {
    public ChatMessage(String role, String content) {
      this(role, content, null, List.of());
    }

    public ChatMessage {
      toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
  }

  public record ToolCall(String id, String name, String arguments) {}

  public record ToolDefinition(String name, String description, Map<String, Object> parameters) {}

  public record ToolChatResult(
      String content, String thinkingContent, List<ToolCall> toolCalls, LlmUsage usage) {
    public ToolChatResult {
      toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public ToolChatResult(String content, String thinkingContent, List<ToolCall> toolCalls) {
      this(content, thinkingContent, toolCalls, null);
    }
  }

  public LlmChatCommand {
    prompt = (prompt == null) ? "" : prompt;
    systemPrompt = (systemPrompt == null) ? "" : systemPrompt;
    history = (history == null) ? List.of() : List.copyOf(history);
    temperature = (temperature == null) ? 0.7 : temperature;
  }

  public static LlmChatCommand of(String prompt) {
    return new LlmChatCommand(prompt, null, List.of(), false, 0.7, null);
  }

  public static LlmChatCommand of(String prompt, String systemPrompt) {
    return new LlmChatCommand(prompt, systemPrompt, List.of(), false, 0.7, null);
  }

  public static LlmChatCommand of(String prompt, String systemPrompt, boolean enableThinking) {
    return new LlmChatCommand(prompt, systemPrompt, List.of(), enableThinking, 0.7, null);
  }

  public static LlmChatCommand of(
      String prompt, String systemPrompt, List<ChatMessage> history, boolean enableThinking) {
    return new LlmChatCommand(prompt, systemPrompt, history, enableThinking, 0.7, null);
  }
}
