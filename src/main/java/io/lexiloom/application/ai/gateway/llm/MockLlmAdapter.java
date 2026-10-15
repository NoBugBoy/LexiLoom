package io.lexiloom.application.ai.gateway.llm;

import io.lexiloom.application.ai.enums.LlmModelProviderEnum;
import io.lexiloom.application.ai.model.response.AiChatResponse;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 测试与断网降级 Mock 大模型适配器
 *
 * <p>零外部 API 依赖，提供确定性的单测模拟及无网络环境下的降级兜底。 完整模拟流式 Thinking 与 Message 输出过程。
 */
@Slf4j
@Component
public class MockLlmAdapter implements LlmGateway {

  @Override
  public LlmModelProviderEnum getProvider() {
    return LlmModelProviderEnum.MOCK;
  }

  @Override
  public boolean isAvailable() {
    return true;
  }

  @Override
  public AiChatResponse chat(LlmChatCommand command) {
    long start = System.currentTimeMillis();
    log.info(
        "【MockLlmAdapter】发起模拟同步对话: promptLength={}, enableThinking={}",
        command.prompt() != null ? command.prompt().length() : 0,
        command.enableThinking());
    String thinking = command.enableThinking() ? "【思考过程】已理解用户问题，正在基于知识库上下文检索事实并组织语言..." : null;
    String content = "【Mock 回答】已收到您的提问：「" + command.prompt() + "」。这是基于系统上下文生成的知识库标准回答。";
    long latency = System.currentTimeMillis() - start;
    log.info("【MockLlmAdapter】模拟同步对话完成: 耗时={}ms (Mock 环境无实际 Token 消耗)", latency);
    return AiChatResponse.of(content, thinking, List.of(), latency);
  }

  @Override
  public void streamChat(LlmChatCommand command, LlmStreamCallback callback) {
    if (callback == null) {
      return;
    }

    long start = System.currentTimeMillis();
    log.info(
        "【MockLlmAdapter】发起模拟流式对话: promptLength={}, enableThinking={}",
        command.prompt() != null ? command.prompt().length() : 0,
        command.enableThinking());
    try {
      if (command.enableThinking()) {
        callback.onThinking("正在思考...");
        callback.onThinking("分析用户意图与切片证据...");
      }

      callback.onMessage("【Mock 回答】");
      callback.onMessage("已收到提问：");
      callback.onMessage(command.prompt());
      long latency = System.currentTimeMillis() - start;
      log.info("【MockLlmAdapter】模拟流式对话完成: 耗时={}ms (Mock 环境无实际 Token 消耗)", latency);
      callback.onComplete();
    } catch (Exception e) {
      callback.onError(e);
    }
  }
}
