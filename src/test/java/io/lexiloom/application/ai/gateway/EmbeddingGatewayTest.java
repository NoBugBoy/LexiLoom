package io.lexiloom.application.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.enums.EmbeddingProviderEnum;
import io.lexiloom.application.ai.gateway.embedding.DashScopeEmbeddingAdapter;
import io.lexiloom.application.ai.gateway.embedding.DefaultEmbeddingGateway;
import io.lexiloom.application.ai.gateway.embedding.OpenAiCompatibleEmbeddingClient;
import io.lexiloom.application.ai.gateway.embedding.OpenAiCompatibleEmbeddingAdapter;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmbeddingGatewayTest {

  @Test
  void routesToConfiguredProvider() {
    AiProperties properties = new AiProperties();
    properties.getEmbedding().setProvider(EmbeddingProviderEnum.OPENAI_COMPATIBLE);
    properties.getEmbedding().setApiKey("test-key");
    properties.getEmbedding().setBaseUrl("https://example.com/v1");
    OpenAiCompatibleEmbeddingClient client = mock(OpenAiCompatibleEmbeddingClient.class);
    var adapter = new OpenAiCompatibleEmbeddingAdapter(client, properties);
    var dashScope = new DashScopeEmbeddingAdapter(client, properties);
    float[] expected = {0.2f, 0.4f};
    when(client.embedBatch(List.of("hello"), "https://example.com/v1", "test-key", "qwen3.7-text-embedding-flash", 1024))
        .thenReturn(List.of(expected));
    var gateway = new DefaultEmbeddingGateway(List.of(adapter, dashScope), properties);

    assertThat(gateway.embed("hello")).isEqualTo(expected);
    assertThat(gateway.getProvider()).isEqualTo(EmbeddingProviderEnum.OPENAI_COMPATIBLE);
  }
}
