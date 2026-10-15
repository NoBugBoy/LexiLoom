package io.lexiloom.application.ai.gateway.llm;

import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.enums.LlmModelProviderEnum;
import io.lexiloom.application.ai.model.response.AiChatResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * 统一大模型防腐层门面实现 (DefaultLlmGateway)
 *
 * <p>基于 CQRS 架构防腐层原则，统一解耦上层 RAG 编排逻辑与底层大模型实现。 依据 {@link AiProperties#getModelProvider()}
 * 动态路由到对应的适配器， 若未配置或外部模型暂不可用，自动平滑降级至 Mock 适配器。
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class DefaultLlmGateway implements LlmGateway {

  private final List<LlmGateway> adapters;
  private final AiProperties aiProperties;

  @Override
  public AiChatResponse chat(LlmChatCommand command) {
    LlmGateway selected = selectAdapter();
    log.info(
        "【LlmGateway】路由同步对话请求至提供商: provider={}, promptLength={}",
        selected.getProvider(),
        command.prompt() != null ? command.prompt().length() : 0);
    return selected.chat(command);
  }

  @Override
  public void streamChat(LlmChatCommand command, LlmStreamCallback callback) {
    LlmGateway selected = selectAdapter();
    log.info(
        "【LlmGateway】路由流式对话请求至提供商: provider={}, promptLength={}",
        selected.getProvider(),
        command.prompt() != null ? command.prompt().length() : 0);
    selected.streamChat(command, callback);
  }

  @Override
  public LlmModelProviderEnum getProvider() {
    return selectAdapter().getProvider();
  }

  private LlmGateway selectAdapter() {
    LlmModelProviderEnum targetProvider = aiProperties.getModelProvider();
    if (targetProvider == null) {
      targetProvider = LlmModelProviderEnum.MOCK;
    }

    final LlmModelProviderEnum finalTarget = targetProvider;
    LlmGateway matched =
        adapters.stream()
            .filter(a -> a != this)
            .filter(a -> a.getProvider() == finalTarget && a.isAvailable())
            .findFirst()
            .orElse(null);

    if (matched != null) {
      return matched;
    }

    log.warn("【LlmGateway】配置的模型提供商 [{}] 不可用或未配置，自动降级为 MOCK 适配器", targetProvider);
    return adapters.stream()
        .filter(a -> a != this)
        .filter(a -> a.getProvider() == LlmModelProviderEnum.MOCK)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("系统缺少基础 MockLlmAdapter 适配器"));
  }
}
