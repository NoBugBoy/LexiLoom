package io.lexiloom.application.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.enums.LlmModelProviderEnum;
import io.lexiloom.application.ai.gateway.llm.DeepSeekLlmAdapter;
import io.lexiloom.application.ai.gateway.llm.DefaultLlmGateway;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand;
import io.lexiloom.application.ai.gateway.llm.LlmStreamCallback;
import io.lexiloom.application.ai.gateway.llm.MockLlmAdapter;
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
  private MockLlmAdapter mockAdapter;
  private ChatModel springAiChatModel;
  private DeepSeekLlmAdapter deepSeekAdapter;
  private DefaultLlmGateway gateway;

  @BeforeEach
  void setUp() {
    properties = new AiProperties();
    mockAdapter = new MockLlmAdapter();
    springAiChatModel = mock(ChatModel.class);
    deepSeekAdapter = new DeepSeekLlmAdapter(springAiChatModel);

    gateway = new DefaultLlmGateway(List.of(mockAdapter, deepSeekAdapter), properties);
  }

  @Test
  @DisplayName("DefaultLlmGateway 路由：默认 MOCK 配置时路由至 MockLlmAdapter")
  void testRoutesToMockWhenConfiguredAsMock() {
    properties.setModelProvider(LlmModelProviderEnum.MOCK);

    LlmChatCommand command = LlmChatCommand.of("你好", "系统设定", true);
    AiChatResponse response = gateway.chat(command);

    assertThat(response.content()).contains("【Mock 回答】");
    assertThat(response.thinkingContent()).contains("【思考过程】");
    assertThat(gateway.getProvider()).isEqualTo(LlmModelProviderEnum.MOCK);
  }

  @Test
  @DisplayName("DefaultLlmGateway 路由：配置 DEEPSEEK 且可用时路由至 DeepSeekLlmAdapter")
  void testRoutesToDeepSeekWhenConfigured() {
    properties.setModelProvider(LlmModelProviderEnum.DEEPSEEK);

    Generation generation = new Generation(new AssistantMessage("DeepSeek 回复内容"));
    ChatResponse chatResponse = new ChatResponse(List.of(generation));
    when(springAiChatModel.call(any(Prompt.class))).thenReturn(chatResponse);

    LlmChatCommand command = LlmChatCommand.of("写一段代码");
    AiChatResponse response = gateway.chat(command);

    assertThat(response.content()).isEqualTo("DeepSeek 回复内容");
    assertThat(gateway.getProvider()).isEqualTo(LlmModelProviderEnum.DEEPSEEK);
  }

  @Test
  @DisplayName("DefaultLlmGateway 路由容错：目标模型未配置时优雅降级为 MockLlmAdapter")
  void testFallbackToMockWhenTargetNotConfigured() {
    properties.setModelProvider(LlmModelProviderEnum.QWEN); // 未注册 Qwen 适配器

    LlmChatCommand command = LlmChatCommand.of("测试降级");
    AiChatResponse response = gateway.chat(command);

    assertThat(response.content()).contains("【Mock 回答】");
    assertThat(gateway.getProvider()).isEqualTo(LlmModelProviderEnum.MOCK);
  }

  @Test
  @DisplayName("MockLlmAdapter 流式调用：正确触发 thinking、message 与 complete 回调")
  void testMockLlmAdapterStreaming() {
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

    LlmChatCommand command = LlmChatCommand.of("流式提问", "人设", true);
    mockAdapter.streamChat(command, callback);

    assertThat(thinkings).isNotEmpty();
    assertThat(messages).isNotEmpty();
    assertThat(completed.get()).isTrue();
  }

  @Test
  @DisplayName("DeepSeekLlmAdapter 流式调用：响应式 Flux 接收增量并解析 <think> 思维链")
  void testDeepSeekLlmAdapterStreamingWithThinking() {
    Generation g1 = new Generation(new AssistantMessage("<think>分析问题"));
    Generation g2 = new Generation(new AssistantMessage("完成思考</think>这是回答正文"));
    when(springAiChatModel.stream(any(Prompt.class)))
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
}
