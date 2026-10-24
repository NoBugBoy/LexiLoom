package io.lexiloom.application.ai.gateway.llm;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.enums.LlmModelProviderEnum;
import io.lexiloom.application.ai.model.response.AiChatResponse;
import io.lexiloom.application.ai.trace.RagTraceHolder;
import io.lexiloom.application.ai.trace.model.RagTraceContext;
import io.lexiloom.application.common.ErrorCode;
import io.lexiloom.application.exception.BusinessException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 基于 Spring AI 官方标准生态抽象的 ChatModel 适配器基类
 *
 * <p>将系统的 {@link LlmChatCommand} 映射为 Spring AI 的 {@link Prompt}，并统一对接 同步 {@code chatModel.call()}
 * 与异步响应式流 {@code chatModel.stream()}。 具备思维链标签 (如 DeepSeek-R1 &lt;think&gt;...&lt;/think&gt;)
 * 的智能流式拆包与路由能力。
 */
@Slf4j
public abstract class SpringAiChatModelAdapter implements LlmGateway {

  protected final ChatModel chatModel;
  protected final LlmModelProviderEnum provider;

  protected SpringAiChatModelAdapter(ChatModel chatModel, LlmModelProviderEnum provider) {
    this.chatModel = chatModel;
    this.provider = provider;
  }

  @Override
  public LlmModelProviderEnum getProvider() {
    return this.provider;
  }

  @Override
  public boolean isAvailable() {
    return chatModel != null;
  }

  @Override
  public AiChatResponse chat(LlmChatCommand command) {
    if (chatModel == null) {
      throw new BusinessException(
          ErrorCode.Ai.LLM_API_ERROR, "未配置对应 Spring AI ChatModel 实例: " + provider);
    }

    long start = System.currentTimeMillis();
    log.info(
        "【LlmGateway】发起同步对话请求: provider={}, promptLength={}, enableThinking={}",
        provider,
        command.prompt() != null ? command.prompt().length() : 0,
        command.enableThinking());
    try {
      Prompt prompt = buildSpringAiPrompt(command);
      ChatResponse response = chatModel.call(prompt);

      String content = "";
      String thinking = null;

      if (response != null && response.getResult() != null) {
        Generation generation = response.getResult();
        String rawOutput = (generation.getOutput() != null) ? generation.getOutput().getText() : "";
        // 拆分可能存在的 <think> 思维链标签
        ThinkingSplit split = extractThinking(rawOutput);
        content = split.content();
        thinking = split.thinking();
      }

      long latency = System.currentTimeMillis() - start;
      Usage usage =
          (response != null && response.getMetadata() != null)
              ? response.getMetadata().getUsage()
              : null;
      Integer promptTokens = null;
      Integer completionTokens = null;
      Integer totalTokens = null;

      if (usage != null) {
        promptTokens = usage.getPromptTokens() != null ? usage.getPromptTokens().intValue() : null;
        completionTokens =
            usage.getGenerationTokens() != null ? usage.getGenerationTokens().intValue() : null;
        totalTokens = usage.getTotalTokens() != null ? usage.getTotalTokens().intValue() : null;

        RagTraceContext traceContext = RagTraceHolder.get();
        if (traceContext != null) {
          traceContext.recordLlmTokens(usage.getPromptTokens(), usage.getGenerationTokens());
        }

        log.info(
            "【LlmGateway】同步对话完成: provider={}, 耗时={}ms, 回复字符数={}, Token消耗=[prompt={}, completion={},"
                + " total={}]",
            provider,
            latency,
            content.length(),
            usage.getPromptTokens(),
            usage.getGenerationTokens(),
            usage.getTotalTokens());
      } else {
        log.info(
            "【LlmGateway】同步对话完成: provider={}, 耗时={}ms, 回复字符数={} (未返回 Token 消耗)",
            provider,
            latency,
            content.length());
      }

      return AiChatResponse.of(
          content,
          thinking,
          List.of(),
          latency,
          null,
          null,
          promptTokens,
          completionTokens,
          totalTokens);
    } catch (Exception e) {
      log.error("【SpringAiChatModelAdapter】调用模型 [{}] 异常: {}", provider, e.getMessage(), e);
      throw new BusinessException(ErrorCode.Ai.LLM_API_ERROR, "大模型对话调用异常: " + e.getMessage());
    }
  }

  @Override
  public void streamChat(LlmChatCommand command, LlmStreamCallback callback) {
    if (chatModel == null) {
      callback.onError(
          new BusinessException(
              ErrorCode.Ai.LLM_API_ERROR, "未配置对应 Spring AI ChatModel: " + provider));
      return;
    }

    long start = System.currentTimeMillis();
    log.info(
        "【LlmGateway】发起流式对话请求: provider={}, promptLength={}, enableThinking={}",
        provider,
        command.prompt() != null ? command.prompt().length() : 0,
        command.enableThinking());
    try {
      Prompt prompt = buildSpringAiPrompt(command);
      Flux<ChatResponse> streamFlux = chatModel.stream(prompt);

      // 跟踪流中思维链标签状态 (<think> ... </think>)
      AtomicBoolean inThinkingTag = new AtomicBoolean(false);
      AtomicReference<Usage> streamUsageRef = new AtomicReference<>(null);

      streamFlux.subscribe(
          chatResponse -> {
            if (chatResponse != null) {
              if (chatResponse.getMetadata() != null
                  && chatResponse.getMetadata().getUsage() != null) {
                streamUsageRef.set(chatResponse.getMetadata().getUsage());
              }
              if (chatResponse.getResult() != null) {
                String delta =
                    (chatResponse.getResult().getOutput() != null)
                        ? chatResponse.getResult().getOutput().getText()
                        : "";
                if (StrUtil.isNotEmpty(delta)) {
                  processStreamDelta(delta, inThinkingTag, callback);
                }
              }
            }
          },
          error -> {
            long latency = System.currentTimeMillis() - start;
            log.error(
                "【SpringAiChatModelAdapter】流式调用模型 [{}] 异常: 耗时={}ms, err={}",
                provider,
                latency,
                error.getMessage(),
                error);
            callback.onError(error);
          },
          () -> {
            long latency = System.currentTimeMillis() - start;
            Usage usage = streamUsageRef.get();
            if (usage != null) {
              RagTraceContext traceContext = RagTraceHolder.get();
              if (traceContext != null) {
                traceContext.recordLlmTokens(usage.getPromptTokens(), usage.getGenerationTokens());
              }
              log.info(
                  "【LlmGateway】流式对话完成: provider={}, 耗时={}ms, Token消耗=[prompt={}, completion={},"
                      + " total={}]",
                  provider,
                  latency,
                  usage.getPromptTokens(),
                  usage.getGenerationTokens(),
                  usage.getTotalTokens());
            } else {
              log.info(
                  "【LlmGateway】流式对话完成: provider={}, 耗时={}ms (未返回 Token 消耗)", provider, latency);
            }
            callback.onComplete(usage);
          });
    } catch (Exception e) {
      log.error("【SpringAiChatModelAdapter】发起模型流式调用 [{}] 异常: {}", provider, e.getMessage(), e);
      callback.onError(e);
    }
  }

  protected Prompt buildSpringAiPrompt(LlmChatCommand command) {
    List<Message> messages = new ArrayList<>();

    // 1. 系统角色设定
    if (StrUtil.isNotBlank(command.systemPrompt())) {
      messages.add(new SystemMessage(command.systemPrompt()));
    }

    // 2. 历史会话窗口
    if (command.history() != null && !command.history().isEmpty()) {
      for (LlmChatCommand.ChatMessage hist : command.history()) {
        if ("assistant".equalsIgnoreCase(hist.role())) {
          messages.add(new AssistantMessage(hist.content()));
        } else {
          messages.add(new UserMessage(hist.content()));
        }
      }
    }

    // 3. 当前用户提问
    messages.add(new UserMessage(command.prompt()));
    return new Prompt(messages);
  }

  /** 流式增量内容处理：精细切分 &lt;think&gt; 与 &lt;/think&gt; 状态流转并派发给对应回调 */
  private void processStreamDelta(
      String delta, AtomicBoolean inThinkingTag, LlmStreamCallback callback) {
    if (StrUtil.isEmpty(delta)) {
      return;
    }

    if (delta.contains("<think>")) {
      String before = StrUtil.subBefore(delta, "<think>", false);
      String after = StrUtil.subAfter(delta, "<think>", false);
      inThinkingTag.set(true);
      if (StrUtil.isNotEmpty(before)) {
        callback.onMessage(before);
      }
      if (StrUtil.isNotEmpty(after)) {
        if (after.contains("</think>")) {
          String thinkingPart = StrUtil.subBefore(after, "</think>", false);
          String messagePart = StrUtil.subAfter(after, "</think>", false);
          inThinkingTag.set(false);
          if (StrUtil.isNotEmpty(thinkingPart)) {
            callback.onThinking(thinkingPart);
          }
          if (StrUtil.isNotEmpty(messagePart)) {
            callback.onMessage(messagePart);
          }
        } else {
          callback.onThinking(after);
        }
      }
      return;
    }

    if (delta.contains("</think>")) {
      String thinkingPart = StrUtil.subBefore(delta, "</think>", false);
      String messagePart = StrUtil.subAfter(delta, "</think>", false);
      inThinkingTag.set(false);
      if (StrUtil.isNotEmpty(thinkingPart)) {
        callback.onThinking(thinkingPart);
      }
      if (StrUtil.isNotEmpty(messagePart)) {
        callback.onMessage(messagePart);
      }
      return;
    }

    if (inThinkingTag.get()) {
      callback.onThinking(delta);
    } else {
      callback.onMessage(delta);
    }
  }

  private ThinkingSplit extractThinking(String rawText) {
    if (StrUtil.isBlank(rawText)) {
      return new ThinkingSplit("", null);
    }
    if (rawText.contains("<think>") && rawText.contains("</think>")) {
      String thinking = StrUtil.subBetween(rawText, "<think>", "</think>");
      String content = StrUtil.subAfter(rawText, "</think>", false).trim();
      return new ThinkingSplit(content, thinking != null ? thinking.trim() : null);
    }
    return new ThinkingSplit(rawText, null);
  }

  private record ThinkingSplit(String content, String thinking) {}
}
