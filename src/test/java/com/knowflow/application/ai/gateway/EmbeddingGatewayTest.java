package com.knowflow.application.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.knowflow.application.ai.config.AiProperties;
import com.knowflow.application.ai.enums.EmbeddingProviderEnum;
import com.knowflow.application.ai.gateway.embedding.DashScopeEmbeddingAdapter;
import com.knowflow.application.ai.gateway.embedding.DefaultEmbeddingGateway;
import com.knowflow.application.ai.gateway.embedding.MockEmbeddingAdapter;
import com.knowflow.application.ai.gateway.embedding.OpenAiCompatibleEmbeddingAdapter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

class EmbeddingGatewayTest {

  private AiProperties properties;
  private MockEmbeddingAdapter mockAdapter;
  private EmbeddingModel springAiEmbeddingModel;
  private OpenAiCompatibleEmbeddingAdapter openAiAdapter;
  private DashScopeEmbeddingAdapter dashScopeAdapter;
  private DefaultEmbeddingGateway gateway;

  @BeforeEach
  void setUp() {
    properties = new AiProperties();
    mockAdapter = new MockEmbeddingAdapter();
    springAiEmbeddingModel = mock(EmbeddingModel.class);
    openAiAdapter = new OpenAiCompatibleEmbeddingAdapter(springAiEmbeddingModel, 1536);
    dashScopeAdapter = new DashScopeEmbeddingAdapter(springAiEmbeddingModel, 1536);

    gateway =
        new DefaultEmbeddingGateway(
            List.of(mockAdapter, openAiAdapter, dashScopeAdapter), properties);
  }

  @Test
  @DisplayName("MockEmbeddingAdapter：向量维度为 1536 且 L2 模长归一化为 1.0 (单位向量)")
  void testMockEmbeddingDimensionAndUnitNorm() {
    float[] vector = mockAdapter.embed("测试段落内容");

    assertThat(vector).hasSize(1536);

    double sumSq = 0.0;
    for (float v : vector) {
      sumSq += v * v;
    }
    // L2 范数约为 1.0 (浮点精度容差 0.01)
    assertThat(Math.sqrt(sumSq)).isBetween(0.99, 1.01);
  }

  @Test
  @DisplayName("MockEmbeddingAdapter：相同输入生成确定性一致的向量输出")
  void testMockEmbeddingDeterminism() {
    float[] v1 = mockAdapter.embed("知识库核心设计");
    float[] v2 = mockAdapter.embed("知识库核心设计");
    float[] v3 = mockAdapter.embed("不同的内容");

    assertThat(v1).isEqualTo(v2);
    assertThat(v1).isNotEqualTo(v3);
  }

  @Test
  @DisplayName("MockEmbeddingAdapter：批量向量化数量与顺序严格匹配")
  void testMockEmbeddingBatch() {
    List<String> texts = List.of("段落一", "段落二", "段落三");
    List<float[]> vectors = mockAdapter.embedBatch(texts);

    assertThat(vectors).hasSize(3);
    for (float[] v : vectors) {
      assertThat(v).hasSize(1536);
    }
  }

  @Test
  @DisplayName("DefaultEmbeddingGateway 路由：默认 MOCK 配置时调用 Mock 适配器")
  void testDefaultGatewayRoutesToMock() {
    properties.getEmbedding().setProvider(EmbeddingProviderEnum.MOCK);

    float[] result = gateway.embed("文本");
    assertThat(result).hasSize(1536);
    assertThat(gateway.getProvider()).isEqualTo(EmbeddingProviderEnum.MOCK);
  }

  @Test
  @DisplayName("DefaultEmbeddingGateway 路由：配置 OPENAI_COMPATIBLE 时调用 Spring AI 适配器")
  void testDefaultGatewayRoutesToSpringAi() {
    properties.getEmbedding().setProvider(EmbeddingProviderEnum.OPENAI_COMPATIBLE);

    float[] expected = new float[1536];
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

    float[] expected = new float[1536];
    expected[0] = 0.99f;
    when(springAiEmbeddingModel.embed("阿里通义千问文本")).thenReturn(expected);

    float[] actual = gateway.embed("阿里通义千问文本");
    assertThat(actual).isEqualTo(expected);
    assertThat(gateway.getProvider()).isEqualTo(EmbeddingProviderEnum.DASHSCOPE);
  }

  @Test
  @DisplayName("DefaultEmbeddingGateway 容错：未配置目标时优雅降级为 Mock")
  void testFallbackToMockWhenTargetNotRegistered() {
    properties.getEmbedding().setProvider(EmbeddingProviderEnum.OLLAMA); // 未注册 Ollama 适配器

    float[] result = gateway.embed("降级测试");
    assertThat(result).hasSize(1536);
    assertThat(gateway.getProvider()).isEqualTo(EmbeddingProviderEnum.MOCK);
  }
}
