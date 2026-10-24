package io.lexiloom.application.ai.config;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.gateway.embedding.Spring7OpenAiCompatibleEmbeddingModel;
import io.lexiloom.application.ai.gateway.llm.Spring7OpenAiCompatibleChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring AI 官方标准生态模型 Bean 装配配置类 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AiModelConfiguration {

  private final AiProperties aiProperties;

  /** OpenAI / DashScope 兼容协议通用 EmbeddingModel */
  @Bean(name = {"openAiEmbeddingModel", "dashScopeEmbeddingModel"})
  public EmbeddingModel openAiEmbeddingModel() {
    if (aiProperties == null || aiProperties.getEmbedding() == null) {
      return null;
    }
    AiProperties.EmbeddingProperties props = aiProperties.getEmbedding();
    String apiKey = StrUtil.blankToDefault(props.getApiKey(), aiProperties.getApiKey());
    if (StrUtil.isBlank(apiKey)) {
      log.info("【AiModelConfiguration】未检测到有效 Embedding API Key，跳过初始化真实 OpenAiEmbeddingModel");
      return null;
    }

    String defaultBaseUrl =
        props.getProvider() != null && props.getProvider().name().contains("DASHSCOPE")
            ? "https://dashscope.aliyuncs.com/compatible-mode/v1"
            : "https://api.openai.com/v1";
    String baseUrl = StrUtil.blankToDefault(props.getBaseUrl(), defaultBaseUrl);
    String model = StrUtil.blankToDefault(props.getModel(), "text-embedding-v3");
    int dimension = props.getDimension() > 0 ? props.getDimension() : 1536;

    log.info(
        "【AiModelConfiguration】初始化 Spring AI OpenAiEmbeddingModel: provider={}, baseUrl={},"
            + " model={}, dimension={}",
        props.getProvider(),
        baseUrl,
        model,
        dimension);

    return new Spring7OpenAiCompatibleEmbeddingModel(baseUrl, apiKey, model, dimension);
  }

  /** DeepSeek 官方兼容 ChatModel */
  @Bean("deepSeekChatModel")
  public ChatModel deepSeekChatModel() {
    if (aiProperties == null) {
      return null;
    }
    String apiKey = aiProperties.getApiKey();
    if (StrUtil.isBlank(apiKey)) {
      apiKey = System.getenv("DEEPSEEK_API_KEY");
    }
    if (StrUtil.isBlank(apiKey)) {
      apiKey = System.getenv("AI_API_KEY");
    }
    String resolvedKey = StrUtil.blankToDefault(apiKey, "");

    String baseUrl = StrUtil.blankToDefault(aiProperties.getBaseUrl(), "https://api.deepseek.com");
    String model = StrUtil.blankToDefault(aiProperties.getChatModel(), "deepseek-chat");

    log.info(
        "【AiModelConfiguration】初始化 Spring AI DeepSeek ChatModel (支持运行时 BYOK 覆盖): baseUrl={}, model={}",
        baseUrl,
        model);
    return new Spring7OpenAiCompatibleChatModel(baseUrl, resolvedKey, model);
  }

  /** 通义千问 (Qwen) 兼容 ChatModel */
  @Bean("qwenChatModel")
  public ChatModel qwenChatModel() {
    if (aiProperties == null) {
      return null;
    }
    String apiKey =
        StrUtil.blankToDefault(
            aiProperties.getApiKey(),
            aiProperties.getEmbedding() != null ? aiProperties.getEmbedding().getApiKey() : null);
    if (StrUtil.isBlank(apiKey)) {
      apiKey = System.getenv("DASHSCOPE_API_KEY");
    }
    if (StrUtil.isBlank(apiKey)) {
      apiKey = System.getenv("AI_API_KEY");
    }
    String resolvedKey = StrUtil.blankToDefault(apiKey, "");

    String defaultQwenUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    String baseUrl =
        (aiProperties.getBaseUrl() != null && aiProperties.getBaseUrl().contains("dashscope"))
            ? aiProperties.getBaseUrl()
            : defaultQwenUrl;
    String model = "qwen3.8-flash";

    log.info(
        "【AiModelConfiguration】初始化 Spring AI Qwen ChatModel (支持运行时 BYOK 覆盖): baseUrl={}, model={}",
        baseUrl,
        model);
    return new Spring7OpenAiCompatibleChatModel(baseUrl, resolvedKey, model);
  }
}
