package io.lexiloom.application.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.enums.LlmModelProviderEnum;
import io.lexiloom.application.ai.gateway.llm.DeepSeekLlmAdapter;
import io.lexiloom.application.ai.gateway.llm.DefaultLlmGateway;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand;
import io.lexiloom.application.ai.gateway.llm.LlmStreamCallback;
import io.lexiloom.application.ai.gateway.llm.QwenLlmAdapter;
import io.lexiloom.application.ai.model.response.AiChatResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

class LlmGatewayTest {

  private AiProperties properties;
  private ChatModel deepSeekChatModel;
  private ChatModel qwenChatModel;
  private DeepSeekLlmAdapter deepSeekAdapter;
  private QwenLlmAdapter qwenAdapter;
  private DefaultLlmGateway gateway;

  @BeforeEach
  void setUp() {
    properties = new AiProperties();
    deepSeekChatModel = mock(ChatModel.class);
    qwenChatModel = mock(ChatModel.class);
    deepSeekAdapter = new DeepSeekLlmAdapter(deepSeekChatModel);
    qwenAdapter = new QwenLlmAdapter(qwenChatModel);

    gateway = new DefaultLlmGateway(List.of(deepSeekAdapter, qwenAdapter), properties);
  }

  @Test
  @DisplayName("DefaultLlmGateway 路由：配置 DEEPSEEK 且可用时路由至 DeepSeekLlmAdapter")
  void testRoutesToDeepSeekWhenConfigured() {
    properties.setModelProvider(LlmModelProviderEnum.DEEPSEEK);

    Generation generation = new Generation(new AssistantMessage("DeepSeek 回复内容"));
    ChatResponse chatResponse = new ChatResponse(List.of(generation));
    when(deepSeekChatModel.call(any(Prompt.class))).thenReturn(chatResponse);

    LlmChatCommand command = LlmChatCommand.of("写一段代码");
    AiChatResponse response = gateway.chat(command);

    assertThat(response.content()).isEqualTo("DeepSeek 回复内容");
    assertThat(gateway.getProvider()).isEqualTo(LlmModelProviderEnum.DEEPSEEK);
  }

  @Test
  @DisplayName("DefaultLlmGateway 路由：配置 QWEN 且可用时路由至 QwenLlmAdapter")
  void testRoutesToQwenWhenConfigured() {
    properties.setModelProvider(LlmModelProviderEnum.QWEN);

    Generation generation = new Generation(new AssistantMessage("通义千问回复内容"));
    ChatResponse chatResponse = new ChatResponse(List.of(generation));
    when(qwenChatModel.call(any(Prompt.class))).thenReturn(chatResponse);

    LlmChatCommand command = LlmChatCommand.of("你好千问");
    AiChatResponse response = gateway.chat(command);

    assertThat(response.content()).isEqualTo("通义千问回复内容");
    assertThat(gateway.getProvider()).isEqualTo(LlmModelProviderEnum.QWEN);
  }

  @Test
  @DisplayName("DefaultLlmGateway 路由容错：目标模型适配器不存在且无备用时抛出明确异常")
  void testThrowsExceptionWhenNoAdapterAvailable() {
    DefaultLlmGateway emptyGateway = new DefaultLlmGateway(List.of(), properties);
    properties.setModelProvider(LlmModelProviderEnum.DEEPSEEK);

    LlmChatCommand command = LlmChatCommand.of("测试未配置");
    assertThatThrownBy(() -> emptyGateway.chat(command))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("未配置可用的大语言模型适配器");
  }

  @Test
  @DisplayName("DeepSeekLlmAdapter 流式调用：响应式 Flux 接收增量并解析 <think> 思维链")
  void testDeepSeekLlmAdapterStreamingWithThinking() {
    Generation g1 = new Generation(new AssistantMessage("<think>分析问题"));
    Generation g2 = new Generation(new AssistantMessage("完成思考</think>这是回答正文"));
    when(deepSeekChatModel.stream(any(Prompt.class)))
        .thenReturn(Flux.just(new ChatResponse(List.of(g1)), new ChatResponse(List.of(g2))));

    List<String> thinkings = new ArrayList<>();
    List<String> messages = new ArrayList<>();
    AtomicBoolean completed = new AtomicBoolean(false);

    LlmStreamCallback callback =
        new LlmStreamCallback() {
          @Override
          public void onThinking(String deltaThinking) {
            thinkings.add(deltaThinking);
          }

          @Override
          public void onMessage(String deltaMessage) {
            messages.add(deltaMessage);
          }

          @Override
          public void onComplete() {
            completed.set(true);
          }

          @Override
          public void onError(Throwable throwable) {}
        };

    deepSeekAdapter.streamChat(LlmChatCommand.of("测试"), callback);

    assertThat(thinkings).contains("分析问题", "完成思考");
    assertThat(messages).contains("这是回答正文");
    assertThat(completed.get()).isTrue();
  }

  @Test
  @DisplayName("DeepSeekLlmAdapter 同步调用返回 usage 时正确封装至 AiChatResponse 并沉淀至 RagTraceContext")
  void testDeepSeekLlmAdapterSyncWithUsage() {
    Generation generation = new Generation(new AssistantMessage("回答内容"));
    org.springframework.ai.chat.metadata.Usage mockUsage =
        new org.springframework.ai.chat.metadata.DefaultUsage(150L, 50L, 200L);
    org.springframework.ai.chat.metadata.ChatResponseMetadata metadata =
        org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
            .withUsage(mockUsage)
            .build();
    ChatResponse chatResponse = new ChatResponse(List.of(generation), metadata);
    when(deepSeekChatModel.call(any(Prompt.class))).thenReturn(chatResponse);

    io.lexiloom.application.ai.trace.model.RagTraceContext context =
        new io.lexiloom.application.ai.trace.model.RagTraceContext(
            "trace-llm", "sess-1", 1L, 1L, "测试问题");

    ScopedValue.where(io.lexiloom.application.ai.trace.RagTraceHolder.CURRENT, context)
        .run(
            () -> {
              AiChatResponse response = deepSeekAdapter.chat(LlmChatCommand.of("测试问题"));
              assertThat(response.content()).isEqualTo("回答内容");
              assertThat(response.promptTokens()).isEqualTo(150);
              assertThat(response.completionTokens()).isEqualTo(50);
              assertThat(response.totalTokens()).isEqualTo(200);
            });

    assertThat(context.getPromptTokens()).isEqualTo(150);
    assertThat(context.getCompletionTokens()).isEqualTo(50);
    assertThat(context.getTotalTokens()).isEqualTo(200);
  }

  @Test
  @DisplayName("DeepSeekLlmAdapter 流式调用包含 usage 时正确通知 onComplete(usage) 并沉淀至 RagTraceContext")
  void testDeepSeekLlmAdapterStreamWithUsage() {
    Generation g = new Generation(new AssistantMessage("流式回答"));
    org.springframework.ai.chat.metadata.Usage mockUsage =
        new org.springframework.ai.chat.metadata.DefaultUsage(80L, 20L, 100L);
    org.springframework.ai.chat.metadata.ChatResponseMetadata metadata =
        org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
            .withUsage(mockUsage)
            .build();
    when(deepSeekChatModel.stream(any(Prompt.class)))
        .thenReturn(Flux.just(new ChatResponse(List.of(g), metadata)));

    io.lexiloom.application.ai.trace.model.RagTraceContext context =
        new io.lexiloom.application.ai.trace.model.RagTraceContext(
            "trace-llm-stream", "sess-1", 1L, 1L, "流式测试");

    java.util.concurrent.atomic.AtomicReference<org.springframework.ai.chat.metadata.Usage>
        callbackUsage = new java.util.concurrent.atomic.AtomicReference<>(null);

    ScopedValue.where(io.lexiloom.application.ai.trace.RagTraceHolder.CURRENT, context)
        .run(
            () -> {
              deepSeekAdapter.streamChat(
                  LlmChatCommand.of("流式测试"),
                  new LlmStreamCallback() {
                    @Override
                    public void onThinking(String deltaThinking) {}

                    @Override
                    public void onMessage(String deltaMessage) {}

                    @Override
                    public void onComplete() {}

                    @Override
                    public void onComplete(org.springframework.ai.chat.metadata.Usage usage) {
                      callbackUsage.set(usage);
                    }

                    @Override
                    public void onError(Throwable throwable) {}
                  });
            });

    assertThat(callbackUsage.get()).isNotNull();
    assertThat(callbackUsage.get().getPromptTokens()).isEqualTo(80L);
    assertThat(callbackUsage.get().getGenerationTokens()).isEqualTo(20L);
    assertThat(context.getPromptTokens()).isEqualTo(80);
    assertThat(context.getCompletionTokens()).isEqualTo(20);
    assertThat(context.getTotalTokens()).isEqualTo(100);
  }

  @Test
  @DisplayName("DefaultLlmGateway BYOK 动态路由：全局配置 DEEPSEEK 但凭证指定 QWEN 时动态路由至 Qwen")
  void testRoutesToQwenWhenByokConfigured() {
    properties.setModelProvider(LlmModelProviderEnum.DEEPSEEK);

    io.lexiloom.application.common.security.OpenApiPrincipal principal =
        new io.lexiloom.application.common.security.OpenApiPrincipal(
            1L, 100L, "BYOK-Key", "chat:completion", null, "QWEN", "sk-user-qwen-key");
    io.lexiloom.application.common.SecurityHolder.setAuthentication(
        100L, List.of(), "lx_live_123", principal);

    try {
      Generation generation = new Generation(new AssistantMessage("Qwen BYOK 回复"));
      ChatResponse chatResponse = new ChatResponse(List.of(generation));
      when(qwenChatModel.call(any(Prompt.class))).thenReturn(chatResponse);

      LlmChatCommand command = LlmChatCommand.of("你好");
      AiChatResponse response = gateway.chat(command);

      assertThat(response.content()).isEqualTo("Qwen BYOK 回复");
      assertThat(gateway.getProvider()).isEqualTo(LlmModelProviderEnum.QWEN);
    } finally {
      org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
  }
}
