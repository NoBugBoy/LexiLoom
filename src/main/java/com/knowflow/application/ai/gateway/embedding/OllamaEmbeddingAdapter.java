package com.knowflow.application.ai.gateway.embedding;

import com.knowflow.application.ai.config.AiProperties;
import com.knowflow.application.ai.enums.EmbeddingProviderEnum;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 本地私有化 Ollama 向量化嵌入适配器 (如 bge-m3, nomic-embed-text)
 *
 * <p>基于 Spring AI 驱动。
 */
@Component
public class OllamaEmbeddingAdapter extends SpringAiEmbeddingAdapter {

  @Autowired
  public OllamaEmbeddingAdapter(
      @Autowired(required = false) @Qualifier("ollamaEmbeddingModel") EmbeddingModel embeddingModel,
      AiProperties aiProperties) {
    super(
        embeddingModel,
        EmbeddingProviderEnum.OLLAMA,
        aiProperties != null && aiProperties.getEmbedding() != null
            ? aiProperties.getEmbedding().getDimension()
            : 1536);
  }

  public OllamaEmbeddingAdapter(EmbeddingModel embeddingModel, int dimension) {
    super(embeddingModel, EmbeddingProviderEnum.OLLAMA, dimension);
  }
}
