package io.lexiloom.application.ai.gateway.llm;

import io.lexiloom.application.ai.enums.LlmModelProviderEnum;
import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.config.AiProperties;
import org.springframework.stereotype.Component;

/**
 * 阿里通义千问 (Qwen / DashScope) 适配器
 *
 * <p>通过 OpenAI-compatible Chat Completions 调用。
 */
@Component
public class QwenLlmAdapter extends OpenAiCompatibleLlmAdapter {

  public QwenLlmAdapter(OpenAiCompatibleChatClient client, AiProperties properties) {
    super(
        client,
        LlmModelProviderEnum.QWEN,
        resolveBaseUrl(properties),
        resolveApiKey(properties),
        "qwen3.8-flash");
  }

  private static String resolveBaseUrl(AiProperties properties) {
    String configured = properties.getBaseUrl();
    return configured != null && configured.contains("dashscope")
        ? configured
        : "https://dashscope.aliyuncs.com/compatible-mode/v1";
  }

  private static String resolveApiKey(AiProperties properties) {
    String key = properties.getApiKey();
    if (StrUtil.isBlank(key) && properties.getEmbedding() != null) {
      key = properties.getEmbedding().getApiKey();
    }
    if (StrUtil.isBlank(key)) key = System.getenv("DASHSCOPE_API_KEY");
    if (StrUtil.isBlank(key)) key = System.getenv("AI_API_KEY");
    return key;
  }
}
