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
}
