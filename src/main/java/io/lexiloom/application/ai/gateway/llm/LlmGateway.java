package io.lexiloom.application.ai.gateway.llm;

import io.lexiloom.application.ai.enums.LlmModelProviderEnum;
import io.lexiloom.application.ai.model.response.AiChatResponse;
import java.util.List;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ChatMessage;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ToolChatResult;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ToolDefinition;

/**
 * 大模型通用防腐层网关契约 (LlmGateway)
 *
 * <p>统一上层业务与 OpenAI-compatible 模型 HTTP 实现之间的交互标准。
 */
public interface LlmGateway {

  /**
   * 同步阻塞式模型对话
   *
   * @param command 对话命令
   * @return 完整回复内容（含思考过程与耗时）
   */
  AiChatResponse chat(LlmChatCommand command);

  /** Synchronous OpenAI-compatible tool-call step. */
  default ToolChatResult chatWithTools(
      LlmChatCommand command, List<ChatMessage> messages, List<ToolDefinition> tools) {
    AiChatResponse response = chat(command);
    return new ToolChatResult(response.content(), response.thinkingContent(), List.of());
  }

  /**
   * 异步流式模型对话
   *
   * @param command 对话命令
   * @param callback 流式事件回调
   */
  void streamChat(LlmChatCommand command, LlmStreamCallback callback);

  /** Stream a model turn that may request one or more server-side tools. */
  default void streamChatWithTools(
      LlmChatCommand command,
      List<ChatMessage> messages,
      List<ToolDefinition> tools,
      LlmStreamCallback callback) {
    streamChat(command, callback);
  }

  /** 当前网关适配的模型供应商类型 */
  LlmModelProviderEnum getProvider();

  /** 当前引擎是否可用 / 已配置 */
  default boolean isAvailable() {
    return true;
  }
}
