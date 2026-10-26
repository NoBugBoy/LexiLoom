package io.lexiloom.application.ai.gateway.llm;

import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ToolCall;
import java.util.List;

/** LLM 流式输出事件回调。 */
public interface LlmStreamCallback {

  /**
   * 推理思维链增量回调 (如 DeepSeek-R1 reasoning_content)
   *
   * @param deltaThinking 思考过程文本增量
   */
  void onThinking(String deltaThinking);

  /**
   * 正文回答增量回调
   *
   * @param deltaMessage 核心回答正文 Token 增量
   */
  void onMessage(String deltaMessage);

  /** 生成全部完成回调 (历史默认无参方法) */
  void onComplete();

  /**
   * 生成全部完成回调（携带服务商返回的 Token 使用量）。
   */
  default void onComplete(LlmUsage usage) {
    onComplete();
  }

  /** A streamed model turn ended with tool calls instead of a final answer. */
  default void onToolCalls(List<ToolCall> toolCalls, LlmUsage usage) {
    onComplete(usage);
  }

  /**
   * 发生异常回调
   *
   * @param throwable 异常原因
   */
  void onError(Throwable throwable);
}
