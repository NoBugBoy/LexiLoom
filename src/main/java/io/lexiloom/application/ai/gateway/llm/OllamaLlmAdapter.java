package io.lexiloom.application.ai.gateway.llm;

import io.lexiloom.application.ai.enums.LlmModelProviderEnum;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 本地私有化 Ollama 大模型适配器
 *
 * <p>基于 Spring AI ChatModel 驱动。
 */
@Component
public class OllamaLlmAdapter extends SpringAiChatModelAdapter {

  public OllamaLlmAdapter(
      @Autowired(required = false) @Qualifier("ollamaChatModel") ChatModel chatModel) {
    super(chatModel, LlmModelProviderEnum.OLLAMA);
  }
}
