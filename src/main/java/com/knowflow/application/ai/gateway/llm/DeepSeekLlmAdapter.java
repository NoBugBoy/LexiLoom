package com.knowflow.application.ai.gateway.llm;

import com.knowflow.application.ai.enums.LlmModelProviderEnum;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 深度求索 (DeepSeek-V3 / DeepSeek-R1) 适配器
 *
 * <p>基于 Spring AI ChatModel 驱动，原生支持思维链与推理过程 (reasoning_content / &lt;think&gt;)。
 */
@Component
public class DeepSeekLlmAdapter extends SpringAiChatModelAdapter {

  public DeepSeekLlmAdapter(
      @Autowired(required = false) @Qualifier("deepSeekChatModel") ChatModel chatModel) {
    super(chatModel, LlmModelProviderEnum.DEEPSEEK);
  }
}
