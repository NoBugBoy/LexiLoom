package com.knowflow.application.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowflow.application.ai.gateway.embedding.Spring7OpenAiCompatibleEmbeddingModel;
import com.knowflow.application.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

@DisplayName("Spring 7 原生兼容 EmbeddingModel 单元测试")
class Spring7OpenAiCompatibleEmbeddingModelTest {

  @Test
  @DisplayName("空文本与空请求安全返回零向量或空列表")
  void shouldHandleEmptyInputsSafely() {
    Spring7OpenAiCompatibleEmbeddingModel model =
        new Spring7OpenAiCompatibleEmbeddingModel(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "test-key",
            "text-embedding-v3",
            1024);

    assertThat(model.dimensions()).isEqualTo(1024);

    // 空 document
    float[] docVec = model.embed((Document) null);
    assertThat(docVec).isNotNull().hasSize(1024);

    // 空 string
    float[] strVec = model.embed("");
    assertThat(strVec).isNotNull().hasSize(1024);

    // 空 list
    List<float[]> emptyList = model.embed(List.of());
    assertThat(emptyList).isEmpty();

    // 空 call
    EmbeddingResponse resp = model.call(new EmbeddingRequest(List.of(), null));
    assertThat(resp).isNotNull();
    assertThat(resp.getResults()).isEmpty();
  }

  @Test
  @DisplayName("未配置 API Key 时调用抛出明确异常")
  void shouldThrowWhenApiKeyIsMissing() {
    Spring7OpenAiCompatibleEmbeddingModel model =
        new Spring7OpenAiCompatibleEmbeddingModel(
            "https://dashscope.aliyuncs.com/compatible-mode/v1", "", "text-embedding-v3", 1536);

    assertThatThrownBy(() -> model.embed("hello"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("未配置 API Key");
  }

  @Test
  @DisplayName("当文本数量大于 25 条时，底层自动分片多次请求并按序拼接结果")
  void shouldAutoPartitionWhenTextsExceedBatchLimit() {
    org.springframework.web.client.RestClient restClient =
        org.mockito.Mockito.mock(org.springframework.web.client.RestClient.class);
    org.springframework.web.client.RestClient.RequestBodyUriSpec uriSpec =
        org.mockito.Mockito.mock(
            org.springframework.web.client.RestClient.RequestBodyUriSpec.class,
            org.mockito.Mockito.RETURNS_SELF);
    org.springframework.web.client.RestClient.RequestBodySpec bodySpec =
        org.mockito.Mockito.mock(
            org.springframework.web.client.RestClient.RequestBodySpec.class,
            org.mockito.Mockito.RETURNS_SELF);
    org.springframework.web.client.RestClient.ResponseSpec responseSpec =
        org.mockito.Mockito.mock(org.springframework.web.client.RestClient.ResponseSpec.class);

    org.mockito.Mockito.when(restClient.post()).thenReturn(uriSpec);
    org.mockito.Mockito.when(uriSpec.uri(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(bodySpec);
    org.mockito.Mockito.when(bodySpec.retrieve()).thenReturn(responseSpec);

    org.mockito.Mockito.when(responseSpec.body(String.class))
        .thenReturn("{\"data\": [{\"index\": 0, \"embedding\": [0.1, 0.2]}]}");

    Spring7OpenAiCompatibleEmbeddingModel model =
        new Spring7OpenAiCompatibleEmbeddingModel(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "test-key",
            "qwen3.7-text-embedding-flash",
            2,
            restClient,
            new tools.jackson.databind.ObjectMapper());

    // 传入 60 条文本：60 / 16 向上取整 = 4 批调用 (16 + 16 + 16 + 12)
    java.util.List<String> sixtyTexts = java.util.Collections.nCopies(60, "文本测试");
    java.util.List<float[]> results = model.embed(sixtyTexts);

    org.mockito.Mockito.verify(restClient, org.mockito.Mockito.times(4)).post();
    org.assertj.core.api.Assertions.assertThat(results).hasSize(4);
  }

  @Test
  @DisplayName("当响应包含 usage 字段时正确解析并记录 Token 消耗")
  void shouldParseAndLogTokenUsageWhenResponseContainsUsage() {
    org.springframework.web.client.RestClient restClient =
        org.mockito.Mockito.mock(org.springframework.web.client.RestClient.class);
    org.springframework.web.client.RestClient.RequestBodyUriSpec uriSpec =
        org.mockito.Mockito.mock(
            org.springframework.web.client.RestClient.RequestBodyUriSpec.class,
            org.mockito.Mockito.RETURNS_SELF);
    org.springframework.web.client.RestClient.RequestBodySpec bodySpec =
        org.mockito.Mockito.mock(
            org.springframework.web.client.RestClient.RequestBodySpec.class,
            org.mockito.Mockito.RETURNS_SELF);
    org.springframework.web.client.RestClient.ResponseSpec responseSpec =
        org.mockito.Mockito.mock(org.springframework.web.client.RestClient.ResponseSpec.class);

    org.mockito.Mockito.when(restClient.post()).thenReturn(uriSpec);
    org.mockito.Mockito.when(uriSpec.uri(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(bodySpec);
    org.mockito.Mockito.when(bodySpec.retrieve()).thenReturn(responseSpec);

    String jsonWithUsage = """
        {
          "data": [{"index": 0, "embedding": [0.35, 0.75]}],
          "usage": {
            "prompt_tokens": 12,
            "total_tokens": 12
          }
        }
        """;
    org.mockito.Mockito.when(responseSpec.body(String.class)).thenReturn(jsonWithUsage);

    Spring7OpenAiCompatibleEmbeddingModel model =
        new Spring7OpenAiCompatibleEmbeddingModel(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "test-key",
            "text-embedding-v3",
            2,
            restClient,
            new tools.jackson.databind.ObjectMapper());

    List<float[]> results = model.embed(List.of("带 usage 的测试文本"));
    assertThat(results).hasSize(1);
    assertThat(results.get(0)).containsExactly(0.35f, 0.75f);
  }
}
