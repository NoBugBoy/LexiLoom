package com.knowflow.application.ai.gateway.embedding;

import com.knowflow.application.ai.config.AiProperties;
import com.knowflow.application.ai.enums.EmbeddingProviderEnum;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容协议 (DashScope / OpenAI / SiliconFlow) 向量化适配器
 *
 * <p>基于 Spring AI 驱动。
 */
@Component
public class OpenAiCompatibleEmbeddingAdapter extends SpringAiEmbeddingAdapter {

  @Autowired
  public OpenAiCompatibleEmbeddingAdapter(
      @Autowired(required = false) @Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel,
      AiProperties aiProperties) {
    super(
        embeddingModel,
        EmbeddingProviderEnum.OPENAI_COMPATIBLE,
        aiProperties != null && aiProperties.getEmbedding() != null
            ? aiProperties.getEmbedding().getDimension()
            : 1536);
  }

  public OpenAiCompatibleEmbeddingAdapter(EmbeddingModel embeddingModel, int dimension) {
    super(embeddingModel, EmbeddingProviderEnum.OPENAI_COMPATIBLE, dimension);
  }
}
