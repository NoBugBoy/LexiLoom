package io.lexiloom.application.ai.gateway.llm;

/**
 * LLM 流式输出事件回调接口 (Reactive Stream Listener)
 *
 * <p>支持思维链推理过程 (DeepSeek-R1 / Qwen 等) 与正文 Token 的分流实时推送。
 */
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

  /** 生成全部完成回调 */
  void onComplete();

  /**
   * 发生异常回调
   *
   * @param throwable 异常原因
   */
  void onError(Throwable throwable);
}
