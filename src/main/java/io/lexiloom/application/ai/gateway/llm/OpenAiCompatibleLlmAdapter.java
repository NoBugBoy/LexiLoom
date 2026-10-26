package io.lexiloom.application.ai.gateway.llm;

import io.lexiloom.application.ai.enums.LlmModelProviderEnum;
import io.lexiloom.application.ai.model.response.AiChatResponse;
import org.springframework.util.StringUtils;
import java.util.List;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ChatMessage;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ToolChatResult;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ToolDefinition;

/** Chat gateway configured for one OpenAI-compatible provider. */
public class OpenAiCompatibleLlmAdapter implements LlmGateway {

  private final OpenAiCompatibleChatClient client;
  private final LlmModelProviderEnum provider;
  private final String baseUrl;
  private final String apiKey;
  private final String model;

  public OpenAiCompatibleLlmAdapter(
      OpenAiCompatibleChatClient client,
      LlmModelProviderEnum provider,
      String baseUrl,
      String apiKey,
      String model) {
    this.client = client;
    this.provider = provider;
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
    this.model = model;
  }

  @Override
  public AiChatResponse chat(LlmChatCommand command) {
    return client.chat(command, baseUrl, apiKey, model);
  }

  @Override
  public ToolChatResult chatWithTools(
      LlmChatCommand command, List<ChatMessage> messages, List<ToolDefinition> tools) {
    return client.chatWithTools(command, messages, tools, baseUrl, apiKey, model);
  }

  @Override
  public void streamChat(LlmChatCommand command, LlmStreamCallback callback) {
    client.stream(command, baseUrl, apiKey, model, callback);
  }

  @Override
  public void streamChatWithTools(
      LlmChatCommand command,
      List<ChatMessage> messages,
      List<ToolDefinition> tools,
      LlmStreamCallback callback) {
    client.streamWithTools(command, messages, tools, baseUrl, apiKey, model, callback);
  }

  @Override
  public LlmModelProviderEnum getProvider() {
    return provider;
  }

  @Override
  public boolean isAvailable() {
    return StringUtils.hasText(apiKey)
        || io.lexiloom.application.common.SecurityHolder.getOpenApiPrincipal()
            .map(p -> StringUtils.hasText(p.llmApiKey()))
            .orElse(false);
  }
}
