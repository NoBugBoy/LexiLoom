package io.lexiloom.application.ai.gateway.llm;

import io.lexiloom.application.ai.enums.LlmModelProviderEnum;
import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.config.AiProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 深度求索 (DeepSeek-V3 / DeepSeek-R1) 适配器
 *
 * <p>通过 OpenAI-compatible Chat Completions 调用，并支持思维链响应字段。
 */
@Component
public class DeepSeekLlmAdapter extends OpenAiCompatibleLlmAdapter {

  public DeepSeekLlmAdapter(OpenAiCompatibleChatClient client, AiProperties properties) {
    super(
        client,
        LlmModelProviderEnum.DEEPSEEK,
        StrUtil.blankToDefault(properties.getBaseUrl(), "https://api.deepseek.com"),
        resolveApiKey(properties),
        StrUtil.blankToDefault(properties.getChatModel(), "deepseek-chat"));
  }

  private static String resolveApiKey(AiProperties properties) {
    String key = properties.getApiKey();
    if (StrUtil.isBlank(key)) key = System.getenv("DEEPSEEK_API_KEY");
    if (StrUtil.isBlank(key)) key = System.getenv("AI_API_KEY");
    return key;
  }
}
