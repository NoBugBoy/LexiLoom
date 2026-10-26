package io.lexiloom.application.ai.gateway.embedding;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.enums.EmbeddingProviderEnum;
import java.util.List;
import org.springframework.stereotype.Component;

/** OpenAI-compatible embedding provider adapter. */
@Component
public class OpenAiCompatibleEmbeddingAdapter implements EmbeddingGateway {

  private final OpenAiCompatibleEmbeddingClient client;
  private final AiProperties properties;

  public OpenAiCompatibleEmbeddingAdapter(
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
    return client.embedBatch(
        texts,
        config.getBaseUrl(),
        resolveApiKey(config.getApiKey()),
        config.getModel(),
        getDimension());
  }

  @Override
  public int getDimension() {
    int dimension = properties.getEmbedding().getDimension();
    return dimension > 0 ? dimension : 1536;
  }

  @Override
  public EmbeddingProviderEnum getProvider() {
    return EmbeddingProviderEnum.OPENAI_COMPATIBLE;
  }

  @Override
  public boolean isAvailable() {
    return StrUtil.isNotBlank(resolveApiKey(properties.getEmbedding().getApiKey()));
  }

  protected String resolveApiKey(String configured) {
    return StrUtil.blankToDefault(configured, properties.getApiKey());
  }
}
