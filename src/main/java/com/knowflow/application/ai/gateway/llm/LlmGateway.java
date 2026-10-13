package com.knowflow.application.ai.gateway.llm;

import com.knowflow.application.ai.enums.LlmModelProviderEnum;
import com.knowflow.application.ai.model.response.AiChatResponse;

/**
 * 大模型通用防腐层网关契约 (LlmGateway)
 *
 * <p>统一上层业务与底层大模型实现之间的交互标准，底层强制基于 Spring AI 驱动。
 */
public interface LlmGateway {

  /**
   * 同步阻塞式模型对话
   *
   * @param command 对话命令
   * @return 完整回复内容（含思考过程与耗时）
   */
  AiChatResponse chat(LlmChatCommand command);

  /**
   * 异步流式模型对话
   *
   * @param command 对话命令
   * @param callback 流式事件回调
   */
  void streamChat(LlmChatCommand command, LlmStreamCallback callback);

  /** 当前网关适配的模型供应商类型 */
  LlmModelProviderEnum getProvider();

  /** 当前引擎是否可用 / 已配置 */
  default boolean isAvailable() {
    return true;
  }
}
