package io.lexiloom.application.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.enums.EmbeddingProviderEnum;
import io.lexiloom.application.ai.gateway.embedding.DashScopeEmbeddingAdapter;
import io.lexiloom.application.ai.gateway.embedding.DefaultEmbeddingGateway;
import io.lexiloom.application.ai.gateway.embedding.OpenAiCompatibleEmbeddingAdapter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

class EmbeddingGatewayTest {

  private AiProperties properties;
  private EmbeddingModel springAiEmbeddingModel;
  private OpenAiCompatibleEmbeddingAdapter openAiAdapter;
  private DashScopeEmbeddingAdapter dashScopeAdapter;
  private DefaultEmbeddingGateway gateway;

  @BeforeEach
  void setUp() {
    properties = new AiProperties();
    springAiEmbeddingModel = mock(EmbeddingModel.class);
    openAiAdapter = new OpenAiCompatibleEmbeddingAdapter(springAiEmbeddingModel, 1024);
    dashScopeAdapter = new DashScopeEmbeddingAdapter(springAiEmbeddingModel, 1024);

    gateway = new DefaultEmbeddingGateway(List.of(openAiAdapter, dashScopeAdapter), properties);
  }

  @Test
  @DisplayName("DefaultEmbeddingGateway 路由：配置 OPENAI_COMPATIBLE 时调用 Spring AI 适配器")
  void testDefaultGatewayRoutesToOpenAiCompatible() {
    properties.getEmbedding().setProvider(EmbeddingProviderEnum.OPENAI_COMPATIBLE);

    float[] expected = new float[1024];
    expected[0] = 0.88f;
    when(springAiEmbeddingModel.embed("Spring AI 文本")).thenReturn(expected);

    float[] actual = gateway.embed("Spring AI 文本");
    assertThat(actual).isEqualTo(expected);
    assertThat(gateway.getProvider()).isEqualTo(EmbeddingProviderEnum.OPENAI_COMPATIBLE);
  }

  @Test
  @DisplayName("DefaultEmbeddingGateway 路由：配置 DASHSCOPE 时调用 DashScope 适配器")
  void testDefaultGatewayRoutesToDashScope() {
    properties.getEmbedding().setProvider(EmbeddingProviderEnum.DASHSCOPE);

    float[] expected = new float[1024];
    expected[0] = 0.99f;
    when(springAiEmbeddingModel.embed("阿里通义千问文本")).thenReturn(expected);

    float[] actual = gateway.embed("阿里通义千问文本");
    assertThat(actual).isEqualTo(expected);
    assertThat(gateway.getProvider()).isEqualTo(EmbeddingProviderEnum.DASHSCOPE);
  }

  @Test
  @DisplayName("DefaultEmbeddingGateway 容错互备：DASHSCOPE 不可用时自动复用 OPENAI_COMPATIBLE 适配器")
  void testFallbackBetweenDashScopeAndOpenAi() {
    DefaultEmbeddingGateway singleGateway =
        new DefaultEmbeddingGateway(List.of(openAiAdapter), properties);
    properties.getEmbedding().setProvider(EmbeddingProviderEnum.DASHSCOPE);

    float[] expected = new float[1024];
    expected[0] = 0.77f;
    when(springAiEmbeddingModel.embed("备选互用测试")).thenReturn(expected);

    float[] actual = singleGateway.embed("备选互用测试");
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  @DisplayName("DefaultEmbeddingGateway 容错：无可用适配器时抛出明确异常")
  void testThrowsExceptionWhenNoAdapterAvailable() {
    DefaultEmbeddingGateway emptyGateway = new DefaultEmbeddingGateway(List.of(), properties);
    properties.getEmbedding().setProvider(EmbeddingProviderEnum.DASHSCOPE);

    assertThatThrownBy(() -> emptyGateway.embed("无适配器测试"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("未配置可用的向量模型适配器");
  }
}
