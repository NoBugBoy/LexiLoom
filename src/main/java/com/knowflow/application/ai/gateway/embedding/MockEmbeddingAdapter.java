package com.knowflow.application.ai.gateway.embedding;

import com.knowflow.application.ai.enums.EmbeddingProviderEnum;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 测试与断网降级 Mock 向量化适配器
 *
 * <p>基于输入文本内容 SHA-256 哈希值作为随机种子，生成确定性的 L2 归一化单位向量 (默认 1536 维)。 确保相同文本生成一致向量，无需任何外部网络或显卡依赖。
 */
@Slf4j
@Component
public class MockEmbeddingAdapter implements EmbeddingGateway {

  private static final int DEFAULT_DIMENSION = 1536;

  @Override
  public float[] embed(String text) {
    if (text == null || text.isBlank()) {
      return new float[DEFAULT_DIMENSION];
    }

    long start = System.currentTimeMillis();
    log.info("【MockEmbedding】生成模拟向量: 文本字符数={}", text.length());

    // 以 SHA-256 前 8 字节作为伪随机种子，保证确定性
    byte[] hash = sha256(text);
    long seed = ByteBuffer.wrap(hash, 0, 8).getLong();
    Random random = new Random(seed);

    float[] vector = new float[DEFAULT_DIMENSION];
    double sumSquares = 0.0;
    for (int i = 0; i < DEFAULT_DIMENSION; i++) {
      float val = (float) (random.nextGaussian());
      vector[i] = val;
      sumSquares += val * val;
    }

    // L2 范数归一化，使其成为单位向量 (余弦相似度 = 点积)
    double norm = Math.sqrt(sumSquares);
    if (norm > 0) {
      for (int i = 0; i < DEFAULT_DIMENSION; i++) {
        vector[i] = (float) (vector[i] / norm);
      }
    }
    long latency = System.currentTimeMillis() - start;
    log.info(
        "【MockEmbedding】模拟向量生成完成: 耗时={}ms, 维度={} (Mock 环境无实际 Token 消耗)",
        latency,
        DEFAULT_DIMENSION);
    return vector;
  }

  @Override
  public List<float[]> embedBatch(List<String> texts) {
    if (texts == null || texts.isEmpty()) {
      return List.of();
    }
    log.info("【MockEmbedding】发起批量模拟向量化: 条数={}", texts.size());
    List<float[]> results = new ArrayList<>(texts.size());
    for (String text : texts) {
      results.add(embed(text));
    }
    return results;
  }

  @Override
  public int getDimension() {
    return DEFAULT_DIMENSION;
  }

  @Override
  public EmbeddingProviderEnum getProvider() {
    return EmbeddingProviderEnum.MOCK;
  }

  @Override
  public boolean isAvailable() {
    return true;
  }

  private byte[] sha256(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return md.digest(input.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm not available", e);
    }
  }
}
