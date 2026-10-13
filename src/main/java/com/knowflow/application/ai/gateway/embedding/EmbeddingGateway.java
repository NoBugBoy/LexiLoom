package com.knowflow.application.ai.gateway.embedding;

import com.knowflow.application.ai.enums.EmbeddingProviderEnum;
import java.util.List;

/**
 * 文本向量化计算通用防腐层网关契约 (EmbeddingGateway)
 *
 * <p>统一上层知识库切片向量化与检索相似度计算标准，底层强制基于 Spring AI EmbeddingModel 驱动。
 */
public interface EmbeddingGateway {

  /**
   * 单文本向量化计算
   *
   * @param text 待向量化的文本（如富化后的上下文帽子+原子段落）
   * @return 稠密浮点向量数组 (Dense Vector)
   */
  float[] embed(String text);

  /**
   * 批量文本向量化计算
   *
   * @param texts 待向量化的文本列表
   * @return 对应的稠密向量列表 (顺序与入参严格一致)
   */
  List<float[]> embedBatch(List<String> texts);

  /** 获取向量维度大小 (默认 1536) */
  int getDimension();

  /** 当前网关适配的嵌入提供商类型 */
  EmbeddingProviderEnum getProvider();

  /** 当前适配器是否可用 */
  default boolean isAvailable() {
    return true;
  }
}
