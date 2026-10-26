package io.lexiloom.application.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.enums.LlmModelProviderEnum;
import io.lexiloom.application.ai.gateway.llm.DeepSeekLlmAdapter;
import io.lexiloom.application.ai.gateway.llm.DefaultLlmGateway;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand;
import io.lexiloom.application.ai.gateway.llm.OpenAiCompatibleChatClient;
import io.lexiloom.application.ai.gateway.llm.QwenLlmAdapter;
import io.lexiloom.application.ai.model.response.AiChatResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LlmGatewayTest {

  private AiProperties properties;
  private OpenAiCompatibleChatClient client;
  private DeepSeekLlmAdapter deepSeek;
  private QwenLlmAdapter qwen;
  private DefaultLlmGateway gateway;

  @BeforeEach
  void setUp() {
    properties = new AiProperties();
    properties.setApiKey("test-key");
    client = mock(OpenAiCompatibleChatClient.class);
    deepSeek = new DeepSeekLlmAdapter(client, properties);
    qwen = new QwenLlmAdapter(client, properties);
    gateway = new DefaultLlmGateway(List.of(deepSeek, qwen), properties);
  }

  @Test
  void routesChatToConfiguredProvider() {
    properties.setModelProvider(LlmModelProviderEnum.QWEN);
    LlmChatCommand command = LlmChatCommand.of("hello");
    AiChatResponse expected = AiChatResponse.of("answer", List.of(), 10);
    when(client.chat(command, "https://dashscope.aliyuncs.com/compatible-mode/v1", "test-key", "qwen3.8-flash"))
        .thenReturn(expected);

    assertThat(gateway.chat(command)).isSameAs(expected);
    assertThat(gateway.getProvider()).isEqualTo(LlmModelProviderEnum.QWEN);
    verify(client).chat(command, "https://dashscope.aliyuncs.com/compatible-mode/v1", "test-key", "qwen3.8-flash");
  }
}
