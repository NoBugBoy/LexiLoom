package com.knowflow.application.ai.gateway.rerank;

import com.knowflow.application.ai.config.AiProperties;
import com.knowflow.application.ai.enums.RerankStrategyEnum;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * 统一重排防腐层门面实现 (DefaultRerankGateway，完全可选与可插拔设计)
 *
 * <p>策略流转： 1. 若配置 {@code rerank.enabled=false}（默认）：自动委派至 {@link NoOpRerankAdapter} 快速透传截断，零额外延迟； 2.
 * 若配置 {@code rerank.enabled=true}：根据 {@link AiProperties.RerankProperties#getStrategy()} 路由至 RRF 或
 * Cross-Encoder 算法组件； 3. 容错兜底：未匹配到适配器时自动降级至 NoOp。
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class DefaultRerankGateway implements RerankGateway {

  private final List<RerankGateway> adapters;
  private final AiProperties aiProperties;

  @Override
  public List<ScoredDocChunkVO> rerank(RerankCommand command) {
    RerankGateway selected = selectAdapter();
    log.info(
        "【RerankGateway】路由重排请求: query=[{}], 候选切块数={}, 匹配策略={}",
        command.query(),
        command.candidates() != null ? command.candidates().size() : 0,
        selected.getStrategy());
    return selected.rerank(command);
  }

  @Override
  public boolean isEnabled() {
    return aiProperties != null
        && aiProperties.getRerank() != null
        && aiProperties.getRerank().isEnabled();
  }

  @Override
  public RerankStrategyEnum getStrategy() {
    return selectAdapter().getStrategy();
  }

  private RerankGateway selectAdapter() {
    if (!isEnabled()) {
      return findAdapter(RerankStrategyEnum.NONE);
    }

    RerankStrategyEnum targetStrategy =
        (aiProperties != null && aiProperties.getRerank() != null)
            ? aiProperties.getRerank().getStrategy()
            : RerankStrategyEnum.NONE;

    if (targetStrategy == null) {
      targetStrategy = RerankStrategyEnum.NONE;
    }

    RerankGateway matched = findAdapter(targetStrategy);
    if (matched != null) {
      return matched;
    }

    log.warn("【RerankGateway】配置的重排策略 [{}] 未找到对应适配器，降级为 NONE 透传", targetStrategy);
    return findAdapter(RerankStrategyEnum.NONE);
  }

  private RerankGateway findAdapter(RerankStrategyEnum strategy) {
    return adapters.stream()
        .filter(a -> a != this)
        .filter(a -> a.getStrategy() == strategy)
        .findFirst()
        .orElseGet(
            () ->
                adapters.stream()
                    .filter(a -> a != this && a.getStrategy() == RerankStrategyEnum.NONE)
                    .findFirst()
                    .orElse(new NoOpRerankAdapter()));
  }
}
