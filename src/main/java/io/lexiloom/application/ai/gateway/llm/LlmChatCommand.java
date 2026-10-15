package io.lexiloom.application.ai.gateway.llm;

import java.util.List;

/** LLM 对话执行命令对象 (不可变 record) */
public record LlmChatCommand(
    String prompt,
    String systemPrompt,
    List<ChatMessage> history,
    boolean enableThinking,
    Double temperature,
    Integer maxTokens) {

  public record ChatMessage(String role, String content) {}

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
