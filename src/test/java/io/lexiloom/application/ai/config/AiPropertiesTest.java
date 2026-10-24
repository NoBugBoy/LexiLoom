package io.lexiloom.application.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.lexiloom.application.ai.enums.EmbeddingProviderEnum;
import io.lexiloom.application.ai.enums.LlmModelProviderEnum;
import io.lexiloom.application.ai.enums.RerankProviderEnum;
import io.lexiloom.application.ai.enums.RerankStrategyEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiPropertiesTest {

  @Test
  @DisplayName("验证 AiProperties 默认值完整性与稳健性")
  void testDefaultProperties() {
    AiProperties properties = new AiProperties();

    // 基础属性默认值
    assertThat(properties.isEnabled()).isTrue();
    assertThat(properties.getModelProvider()).isEqualTo(LlmModelProviderEnum.DEEPSEEK);
    assertThat(properties.getApiKey()).isEmpty();
    assertThat(properties.getBaseUrl()).isEqualTo("https://api.deepseek.com");
    assertThat(properties.getChatModel()).isEqualTo("deepseek-chat");
    assertThat(properties.getReasoningModel()).isEqualTo("deepseek-reasoner");
    assertThat(properties.getChunkIndexName()).isEqualTo("lexiloom_chunk_index");
    assertThat(properties.isAutoCreateIndex()).isTrue();

    // 向量嵌入默认值
    assertThat(properties.getEmbedding()).isNotNull();
    assertThat(properties.getEmbedding().getProvider()).isEqualTo(EmbeddingProviderEnum.DASHSCOPE);
    assertThat(properties.getEmbedding().getModel()).isEqualTo("qwen3.7-text-embedding-flash");
    assertThat(properties.getEmbedding().getDimension()).isEqualTo(1024);

    // 重排默认值 (默认关闭，启用时默认策略为 CROSS_ENCODER)
    assertThat(properties.getRerank()).isNotNull();
    assertThat(properties.getRerank().isEnabled()).isFalse();
    assertThat(properties.getRerank().getStrategy()).isEqualTo(RerankStrategyEnum.CROSS_ENCODER);
    assertThat(properties.getRerank().getProvider()).isEqualTo(RerankProviderEnum.DASHSCOPE);
    assertThat(properties.getRerank().getModel()).isEqualTo("qwen3.7-text-rerank");
    assertThat(properties.getRerank().getTopK()).isEqualTo(12);
    assertThat(properties.getRerank().getMinScore()).isEqualTo(0.60);

    // DeepDoc 上下文帽子与切块默认值
    assertThat(properties.getChunk()).isNotNull();
    assertThat(properties.getChunk().isContextPrefixEnabled()).isTrue();
    assertThat(properties.getChunk().getMaxTokenLimit()).isEqualTo(2000);

    // 在线 RAG 默认值
    assertThat(properties.getRag()).isNotNull();
    assertThat(properties.getRag().getTopK()).isEqualTo(20);
    assertThat(properties.getRag().getVectorTopK()).isEqualTo(15);
    assertThat(properties.getRag().getLexicalTopK()).isEqualTo(5);
    // 阈值语义对齐真实 Cosine 相似度
    assertThat(properties.getRag().getSimilarityThreshold()).isEqualTo(0.30);
    assertThat(properties.getRag().getMaxChunksPerDocument()).isEqualTo(8);
    assertThat(properties.getRag().getHistoryTurns()).isEqualTo(3);
  }

  @Test
  @DisplayName("验证 AiProperties 动态设值与供应商切换")
  void testCustomSetterAndProviderSwitching() {
    AiProperties properties = new AiProperties();

    properties.setEnabled(false);
    properties.setModelProvider(LlmModelProviderEnum.QWEN);
    properties.setApiKey("sk-custom-qwen-key");
    properties.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
    properties.setChatModel("qwen-plus");
    properties.setReasoningModel("qwen-max");
    properties.setChunkIndexName("custom_chunk_index");
    properties.setAutoCreateIndex(false);

    properties.getEmbedding().setProvider(EmbeddingProviderEnum.OPENAI_COMPATIBLE);
    properties.getEmbedding().setApiKey("sk-custom-openai-key");
    properties.getEmbedding().setBaseUrl("https://api.openai.com/v1");
    properties.getEmbedding().setModel("text-embedding-3-small");
    properties.getEmbedding().setDimension(1536);

    properties.getRerank().setEnabled(true);
    properties.getRerank().setStrategy(RerankStrategyEnum.CROSS_ENCODER);
    properties.getRerank().setProvider(RerankProviderEnum.DASHSCOPE);
    properties.getRerank().setApiKey("sk-custom-rerank-key");
    properties.getRerank().setModel("qwen3.7-text-rerank");
    properties.getRerank().setTopK(10);
    properties.getRerank().setMinScore(0.75);

    properties.getChunk().setContextPrefixEnabled(false);
    properties.getChunk().setMaxTokenLimit(4000);

    properties.getRag().setTopK(8);
    properties.getRag().setSimilarityThreshold(0.70);
    properties.getRag().setHistoryTurns(5);

    // 断言变更生效
    assertThat(properties.isEnabled()).isFalse();
    assertThat(properties.getModelProvider()).isEqualTo(LlmModelProviderEnum.QWEN);
    assertThat(properties.getApiKey()).isEqualTo("sk-custom-qwen-key");
    assertThat(properties.getChatModel()).isEqualTo("qwen-plus");
    assertThat(properties.getReasoningModel()).isEqualTo("qwen-max");
    assertThat(properties.getChunkIndexName()).isEqualTo("custom_chunk_index");

    assertThat(properties.getEmbedding().getProvider())
        .isEqualTo(EmbeddingProviderEnum.OPENAI_COMPATIBLE);
    assertThat(properties.getEmbedding().getDimension()).isEqualTo(1536);

    assertThat(properties.getRerank().isEnabled()).isTrue();
    assertThat(properties.getRerank().getStrategy()).isEqualTo(RerankStrategyEnum.CROSS_ENCODER);
    assertThat(properties.getRerank().getTopK()).isEqualTo(10);
    assertThat(properties.getRerank().getMinScore()).isEqualTo(0.75);

    assertThat(properties.getChunk().isContextPrefixEnabled()).isFalse();
    assertThat(properties.getChunk().getMaxTokenLimit()).isEqualTo(4000);

    assertThat(properties.getRag().getTopK()).isEqualTo(8);
    assertThat(properties.getRag().getSimilarityThreshold()).isEqualTo(0.70);
    assertThat(properties.getRag().getHistoryTurns()).isEqualTo(5);
  }
}
