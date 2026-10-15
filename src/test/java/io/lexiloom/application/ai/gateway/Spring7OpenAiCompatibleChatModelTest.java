package io.lexiloom.application.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lexiloom.application.ai.gateway.llm.Spring7OpenAiCompatibleChatModel;
import io.lexiloom.application.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

@DisplayName("Spring 7 原生兼容 ChatModel 单元测试")
class Spring7OpenAiCompatibleChatModelTest {

  @Test
  @DisplayName("未配置 API Key 时同步调用应抛出异常")
  void shouldThrowWhenApiKeyMissingOnCall() {
    Spring7OpenAiCompatibleChatModel model =
        new Spring7OpenAiCompatibleChatModel("https://api.deepseek.com", "", "deepseek-chat");

    Prompt prompt = new Prompt(List.of(new UserMessage("hello")));
    assertThatThrownBy(() -> model.call(prompt))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("未配置 API Key");
  }

  @Test
  @DisplayName("未配置 API Key 时流式调用应返回错误 Flux")
  void shouldReturnErrorFluxWhenApiKeyMissingOnStream() {
    Spring7OpenAiCompatibleChatModel model =
        new Spring7OpenAiCompatibleChatModel("https://api.deepseek.com", "", "deepseek-chat");

    Prompt prompt =
        new Prompt(List.of(new SystemMessage("system prompt"), new UserMessage("question")));
    Flux<ChatResponse> stream = model.stream(prompt);

    assertThatThrownBy(stream::blockFirst)
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("未配置 API Key");
  }

  @Test
  @DisplayName("默认模型配置正确反映到 DefaultOptions")
  void shouldReturnCorrectDefaultOptions() {
    Spring7OpenAiCompatibleChatModel model =
        new Spring7OpenAiCompatibleChatModel("https://api.deepseek.com", "sk-xxx", "qwen-max");

    assertThat(model.getDefaultOptions()).isNotNull();
    assertThat(model.getDefaultOptions().getModel()).isEqualTo("qwen-max");
  }

  @Test
  @DisplayName("同步调用返回 usage 时正确解析并注入 ChatResponseMetadata")
  @SuppressWarnings("unchecked")
  void shouldParseAndPopulateTokenUsageWhenCallResponseContainsUsage() throws Exception {
    java.net.http.HttpClient mockClient = org.mockito.Mockito.mock(java.net.http.HttpClient.class);
    java.net.http.HttpResponse<String> mockResponse =
        org.mockito.Mockito.mock(java.net.http.HttpResponse.class);

    String jsonBody =
        """
        {
          "id": "chatcmpl-123",
          "model": "deepseek-chat",
          "choices": [
            {
              "index": 0,
              "message": {
                "role": "assistant",
                "content": "测试回答",
                "reasoning_content": "思考步骤"
              }
            }
          ],
          "usage": {
            "prompt_tokens": 15,
            "completion_tokens": 25,
            "total_tokens": 40
          }
        }
        """;

    org.mockito.Mockito.when(mockResponse.statusCode()).thenReturn(200);
    org.mockito.Mockito.when(mockResponse.body()).thenReturn(jsonBody);
    org.mockito.Mockito.when(
            mockClient.send(
                org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                org.mockito.ArgumentMatchers.any(java.net.http.HttpResponse.BodyHandler.class)))
        .thenReturn(mockResponse);

    Spring7OpenAiCompatibleChatModel model =
        new Spring7OpenAiCompatibleChatModel(
            "https://api.deepseek.com",
            "sk-test",
            "deepseek-chat",
            mockClient,
            new tools.jackson.databind.ObjectMapper());

    Prompt prompt = new Prompt(List.of(new UserMessage("hello")));
    ChatResponse response = model.call(prompt);

    assertThat(response).isNotNull();
    assertThat(response.getResult().getOutput().getText()).contains("思考步骤", "测试回答");
    assertThat(response.getMetadata()).isNotNull();
    assertThat(response.getMetadata().getUsage()).isNotNull();
    assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(15L);
    assertThat(response.getMetadata().getUsage().getGenerationTokens()).isEqualTo(25L);
    assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(40L);
  }
}
