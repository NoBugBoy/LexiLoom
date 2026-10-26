package io.lexiloom.application.ai.gateway.llm;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.enums.LlmModelProviderEnum;
import io.lexiloom.application.ai.model.response.AiChatResponse;
import io.lexiloom.application.common.SecurityHolder;
import java.util.List;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ChatMessage;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ToolChatResult;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ToolDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * 统一大模型防腐层门面实现 (DefaultLlmGateway)
 *
 * <p>基于 CQRS 架构防腐层原则，统一解耦上层 RAG 编排逻辑与底层大模型实现。依据 {@link AiProperties#getModelProvider()} 动态路由到对应的适配器
 * (DeepSeek / Qwen)。
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
  public ToolChatResult chatWithTools(
      LlmChatCommand command, List<ChatMessage> messages, List<ToolDefinition> tools) {
    return selectAdapter().chatWithTools(command, messages, tools);
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
  public void streamChatWithTools(
      LlmChatCommand command,
      List<ChatMessage> messages,
      List<ToolDefinition> tools,
      LlmStreamCallback callback) {
    selectAdapter().streamChatWithTools(command, messages, tools, callback);
  }

  @Override
  public LlmModelProviderEnum getProvider() {
    return selectAdapter().getProvider();
  }

  private LlmGateway selectAdapter() {
    LlmModelProviderEnum targetProvider = null;

    // 1. 优先读取当前安全上下文中的 OpenAPI BYOK 配置 (用户自带模型厂商)
    var openApiPrincipal = SecurityHolder.getOpenApiPrincipal().orElse(null);
    if (openApiPrincipal != null && StrUtil.isNotBlank(openApiPrincipal.llmProvider())) {
      String p = openApiPrincipal.llmProvider().trim().toUpperCase();
      if ("QWEN".equals(p)) {
        targetProvider = LlmModelProviderEnum.QWEN;
      } else if ("DEEPSEEK".equals(p)) {
        targetProvider = LlmModelProviderEnum.DEEPSEEK;
      }
    }

    // 2. 若未配置则回退使用全局配置默认模型
    if (targetProvider == null) {
      targetProvider = aiProperties != null ? aiProperties.getModelProvider() : null;
    }
    if (targetProvider == null) {
      targetProvider = LlmModelProviderEnum.DEEPSEEK;
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

    // 若配置的提供商不可用，尝试在已注册的可用适配器中查找可用实例（如 QWEN 备选 DEEPSEEK）
    LlmGateway anyAvailable =
        adapters.stream().filter(a -> a != this && a.isAvailable()).findFirst().orElse(null);
    if (anyAvailable != null) {
      log.warn(
          "【LlmGateway】配置的模型提供商 [{}] 不可用，自动切换至可用提供商 [{}]",
          targetProvider,
          anyAvailable.getProvider());
      return anyAvailable;
    }

    throw new IllegalStateException("未配置可用的大语言模型适配器或 API Key 无效，当前目标提供商: " + targetProvider);
  }
}
