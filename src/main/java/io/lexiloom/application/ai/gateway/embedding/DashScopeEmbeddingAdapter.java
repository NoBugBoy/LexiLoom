package io.lexiloom.application.ai.gateway.embedding;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.enums.EmbeddingProviderEnum;
import java.util.List;
import org.springframework.stereotype.Component;

/** DashScope embedding provider adapter using its OpenAI-compatible endpoint. */
@Component
public class DashScopeEmbeddingAdapter implements EmbeddingGateway {

  private final OpenAiCompatibleEmbeddingClient client;
  private final AiProperties properties;

  public DashScopeEmbeddingAdapter(
      OpenAiCompatibleEmbeddingClient client, AiProperties properties) {
    this.client = client;
    this.properties = properties;
  }

  @Override
  public float[] embed(String text) {
    if (StrUtil.isBlank(text)) return new float[getDimension()];
    return embedBatch(List.of(text)).getFirst();
  }

  @Override
  public List<float[]> embedBatch(List<String> texts) {
    var config = properties.getEmbedding();
    String key = StrUtil.blankToDefault(config.getApiKey(), properties.getApiKey());
    if (StrUtil.isBlank(key)) key = System.getenv("DASHSCOPE_API_KEY");
    return client.embedBatch(
        texts,
        StrUtil.blankToDefault(config.getBaseUrl(), "https://dashscope.aliyuncs.com/compatible-mode/v1"),
        key,
        StrUtil.blankToDefault(config.getModel(), "text-embedding-v3"),
        getDimension());
  }

  @Override
  public int getDimension() {
    int dimension = properties.getEmbedding().getDimension();
    return dimension > 0 ? dimension : 1536;
  }

  @Override
  public EmbeddingProviderEnum getProvider() {
    return EmbeddingProviderEnum.DASHSCOPE;
  }

  @Override
  public boolean isAvailable() {
    var config = properties.getEmbedding();
    return StrUtil.isNotBlank(config.getApiKey())
        || StrUtil.isNotBlank(properties.getApiKey())
        || StrUtil.isNotBlank(System.getenv("DASHSCOPE_API_KEY"));
  }
}
