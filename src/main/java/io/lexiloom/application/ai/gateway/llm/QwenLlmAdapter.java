package io.lexiloom.application.ai.gateway.llm;

import io.lexiloom.application.ai.enums.LlmModelProviderEnum;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 阿里通义千问 (Qwen / DashScope) 适配器
 *
 * <p>基于 Spring AI ChatModel 驱动。
 */
@Component
public class QwenLlmAdapter extends SpringAiChatModelAdapter {

  public QwenLlmAdapter(
      @Autowired(required = false) @Qualifier("qwenChatModel") ChatModel chatModel) {
    super(chatModel, LlmModelProviderEnum.QWEN);
  }
}
