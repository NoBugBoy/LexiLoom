package io.lexiloom.application.ai.gateway.embedding;

import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.enums.EmbeddingProviderEnum;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * 统一向量化防腐层门面实现 (DefaultEmbeddingGateway)
 *
 * <p>基于 {@link AiProperties#getEmbedding()} 动态路由到指定向量化提供商 (DashScope / OpenAI Compatible)。
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class DefaultEmbeddingGateway implements EmbeddingGateway {

  private final List<EmbeddingGateway> adapters;
  private final AiProperties aiProperties;

  @Override
  public float[] embed(String text) {
    EmbeddingGateway adapter = selectAdapter();
    log.info(
        "【DefaultEmbeddingGateway】路由单文本向量化: provider={}, 字符数={}",
        adapter.getProvider(),
        text != null ? text.length() : 0);
    return adapter.embed(text);
  }

  @Override
  public List<float[]> embedBatch(List<String> texts) {
    EmbeddingGateway adapter = selectAdapter();
    log.info(
        "【DefaultEmbeddingGateway】路由批量文本向量化: provider={}, 条数={}",
        adapter.getProvider(),
        texts != null ? texts.size() : 0);
    return adapter.embedBatch(texts);
  }

  @Override
  public int getDimension() {
    return selectAdapter().getDimension();
  }

  @Override
  public EmbeddingProviderEnum getProvider() {
    return selectAdapter().getProvider();
  }

  private EmbeddingGateway selectAdapter() {
    EmbeddingProviderEnum target =
        (aiProperties != null && aiProperties.getEmbedding() != null)
            ? aiProperties.getEmbedding().getProvider()
            : null;

    if (target == null) {
      target = EmbeddingProviderEnum.DASHSCOPE;
    }

    final EmbeddingProviderEnum finalTarget = target;
    EmbeddingGateway matched =
        adapters.stream()
            .filter(a -> a != this)
            .filter(a -> a.getProvider() == finalTarget && a.isAvailable())
            .findFirst()
            .orElse(null);

    if (matched != null) {
      return matched;
    }

    // 阿里 DashScope 与 OpenAI 兼容协议同属 OpenAI 标准范畴，支持互相平滑回退
    if (finalTarget == EmbeddingProviderEnum.DASHSCOPE) {
      EmbeddingGateway openAiFallback =
          adapters.stream()
              .filter(a -> a != this)
              .filter(
                  a ->
                      a.getProvider() == EmbeddingProviderEnum.OPENAI_COMPATIBLE && a.isAvailable())
              .findFirst()
              .orElse(null);
      if (openAiFallback != null) {
        log.info("【EmbeddingGateway】供应商 DASHSCOPE 成功复用 OPENAI_COMPATIBLE 适配器");
        return openAiFallback;
      }
    } else if (finalTarget == EmbeddingProviderEnum.OPENAI_COMPATIBLE) {
      EmbeddingGateway dashScopeFallback =
          adapters.stream()
              .filter(a -> a != this)
              .filter(a -> a.getProvider() == EmbeddingProviderEnum.DASHSCOPE && a.isAvailable())
              .findFirst()
              .orElse(null);
      if (dashScopeFallback != null) {
        log.info("【EmbeddingGateway】供应商 OPENAI_COMPATIBLE 成功复用 DASHSCOPE 适配器");
        return dashScopeFallback;
      }
    }

    throw new IllegalStateException("未配置可用的向量模型适配器或 API Key 无效，当前目标提供商: " + target);
  }
}
