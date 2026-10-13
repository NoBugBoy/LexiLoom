package com.knowflow.application.ai.gateway.embedding;

import com.knowflow.application.ai.config.AiProperties;
import com.knowflow.application.ai.enums.EmbeddingProviderEnum;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 阿里 DashScope (通义千问 text-embedding) 向量化适配器
 *
 * <p>基于 Spring AI 驱动。
 */
@Component
public class DashScopeEmbeddingAdapter extends SpringAiEmbeddingAdapter {

  @Autowired
  public DashScopeEmbeddingAdapter(
      @Autowired(required = false) @Qualifier("dashScopeEmbeddingModel")
          EmbeddingModel embeddingModel,
      @Autowired(required = false) @Qualifier("openAiEmbeddingModel") EmbeddingModel fallbackModel,
      AiProperties aiProperties) {
    super(
        embeddingModel != null ? embeddingModel : fallbackModel,
        EmbeddingProviderEnum.DASHSCOPE,
        aiProperties != null && aiProperties.getEmbedding() != null
            ? aiProperties.getEmbedding().getDimension()
            : 1536);
  }

  public DashScopeEmbeddingAdapter(EmbeddingModel embeddingModel, int dimension) {
    super(embeddingModel, EmbeddingProviderEnum.DASHSCOPE, dimension);
  }
}
