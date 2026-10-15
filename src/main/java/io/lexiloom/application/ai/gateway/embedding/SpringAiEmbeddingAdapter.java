package io.lexiloom.application.ai.gateway.embedding;

import io.lexiloom.application.ai.enums.EmbeddingProviderEnum;
import io.lexiloom.application.common.ErrorCode;
import io.lexiloom.application.exception.BusinessException;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;

/** 基于 Spring AI 官方标准 EmbeddingModel 的向量化适配器基类 */
@Slf4j
public class SpringAiEmbeddingAdapter implements EmbeddingGateway {

  private final EmbeddingModel embeddingModel;
  private final EmbeddingProviderEnum provider;
  private final int dimension;

  public SpringAiEmbeddingAdapter(
      EmbeddingModel embeddingModel, EmbeddingProviderEnum provider, int dimension) {
    this.embeddingModel = embeddingModel;
    this.provider = provider;
    this.dimension = dimension > 0 ? dimension : 1536;
  }

  @Override
  public float[] embed(String text) {
    if (embeddingModel == null) {
      throw new BusinessException(
          ErrorCode.Ai.EMBEDDING_FAILED, "未配置对应 Spring AI EmbeddingModel: " + provider);
    }
    if (text == null || text.isBlank()) {
      return new float[dimension];
    }
    long start = System.currentTimeMillis();
    log.info("【EmbeddingGateway】发起单文本向量化: provider={}, 字符数={}", provider, text.length());
    try {
      float[] result = embeddingModel.embed(text);
      long latency = System.currentTimeMillis() - start;
      log.info(
          "【EmbeddingGateway】单文本向量化完成: provider={}, 耗时={}ms, 维度={}",
          provider,
          latency,
          result != null ? result.length : 0);
      return result;
    } catch (Exception e) {
      log.error(
          "【SpringAiEmbeddingAdapter】向量化文本失败: provider={}, err={}", provider, e.getMessage(), e);
      throw new BusinessException(ErrorCode.Ai.EMBEDDING_FAILED, "文本向量化失败: " + e.getMessage());
    }
  }

  @Override
  public List<float[]> embedBatch(List<String> texts) {
    if (embeddingModel == null) {
      throw new BusinessException(
          ErrorCode.Ai.EMBEDDING_FAILED, "未配置对应 Spring AI EmbeddingModel: " + provider);
    }
    if (texts == null || texts.isEmpty()) {
      return Collections.emptyList();
    }
    long start = System.currentTimeMillis();
    int totalChars = texts.stream().mapToInt(t -> t != null ? t.length() : 0).sum();
    log.info(
        "【EmbeddingGateway】发起批量文本向量化: provider={}, 条数={}, 总字符数={}",
        provider,
        texts.size(),
        totalChars);
    try {
      List<float[]> results = embeddingModel.embed(texts);
      long latency = System.currentTimeMillis() - start;
      log.info(
          "【EmbeddingGateway】批量文本向量化完成: provider={}, 条数={}, 耗时={}ms",
          provider,
          results != null ? results.size() : 0,
          latency);
      return results;
    } catch (Exception e) {
      log.error(
          "【SpringAiEmbeddingAdapter】批量向量化失败: provider={}, count={}, err={}",
          provider,
          texts.size(),
          e.getMessage(),
          e);
      throw new BusinessException(ErrorCode.Ai.EMBEDDING_FAILED, "批量文本向量化失败: " + e.getMessage());
    }
  }

  @Override
  public int getDimension() {
    return this.dimension;
  }

  @Override
  public EmbeddingProviderEnum getProvider() {
    return this.provider;
  }

  @Override
  public boolean isAvailable() {
    return embeddingModel != null;
  }
}
