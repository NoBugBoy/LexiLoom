package io.lexiloom.application.ai.gateway.embedding;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.trace.RagTraceHolder;
import io.lexiloom.application.ai.trace.model.RagTraceContext;
import io.lexiloom.application.exception.ErrorCode;
import io.lexiloom.application.exception.BusinessException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** OpenAI-compatible embeddings HTTP client. */
@Slf4j
@Component
public class OpenAiCompatibleEmbeddingClient {

  private static final int MAX_BATCH_SIZE = 16;
  private final ObjectMapper objectMapper;
  private final RestClient restClient;

  @Autowired
  public OpenAiCompatibleEmbeddingClient(ObjectMapper objectMapper) {
    this(objectMapper, null);
  }

  public OpenAiCompatibleEmbeddingClient(
      ObjectMapper objectMapper, @Nullable RestClient restClient) {
    this.objectMapper = objectMapper;
    if (restClient != null) {
      this.restClient = restClient;
    } else {
      HttpClient httpClient =
          HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
      JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
      requestFactory.setReadTimeout(Duration.ofSeconds(60));
      this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }
  }

  public List<float[]> embedBatch(
      List<String> texts, String baseUrl, String apiKey, String model, int dimension) {
    if (texts == null || texts.isEmpty()) return List.of();
    if (StrUtil.isBlank(apiKey)) {
      throw new BusinessException(ErrorCode.Ai.EMBEDDING_FAILED, "未配置向量模型 API Key");
    }
    List<float[]> vectors = new ArrayList<>(texts.size());
    for (int start = 0; start < texts.size(); start += MAX_BATCH_SIZE) {
      int end = Math.min(start + MAX_BATCH_SIZE, texts.size());
      vectors.addAll(embedBatchPart(texts.subList(start, end), baseUrl, apiKey, model, dimension));
    }
    return vectors;
  }

  private List<float[]> embedBatchPart(
      List<String> texts, String baseUrl, String apiKey, String model, int dimension) {
    try {
      String endpoint = normalizeEndpoint(baseUrl);
      Map<String, Object> body = Map.of("model", model, "input", texts, "dimensions", dimension);
      String response =
          restClient
              .post()
              .uri(endpoint)
              .header("Authorization", "Bearer " + apiKey)
              .body(body)
              .retrieve()
              .body(String.class);
      JsonNode root = objectMapper.readTree(response);
      JsonNode data = root.get("data");
      if (data == null || !data.isArray()) {
        throw new IllegalStateException("Embedding 响应缺少 data 数组");
      }
      List<IndexedVector> indexed = new ArrayList<>(data.size());
      for (JsonNode item : data) {
        JsonNode embedding = item.get("embedding");
        if (embedding == null || !embedding.isArray()) continue;
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) vector[i] = (float) embedding.get(i).asDouble();
        indexed.add(new IndexedVector(item.hasNonNull("index") ? item.get("index").asInt() : indexed.size(), vector));
      }
      indexed.sort(Comparator.comparingInt(IndexedVector::index));
      if (indexed.size() != texts.size()) {
        throw new IllegalStateException("Embedding 向量数量与输入文本数量不一致");
      }
      recordUsage(root.get("usage"));
      return indexed.stream().map(IndexedVector::vector).toList();
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      log.error("向量模型请求失败: model={}, error={}", model, e.getMessage());
      throw new BusinessException(ErrorCode.Ai.EMBEDDING_FAILED, "向量模型调用失败: " + e.getMessage());
    }
  }

  private void recordUsage(JsonNode usage) {
    if (usage == null || usage.isNull()) return;
    long tokens =
        usage.hasNonNull("prompt_tokens")
            ? usage.get("prompt_tokens").asLong(0L)
            : usage.path("total_tokens").asLong(0L);
    if (tokens <= 0) return;
    RagTraceContext trace = RagTraceHolder.get();
    if (trace != null) trace.recordEmbeddingTokens(tokens);
  }

  private String normalizeEndpoint(String baseUrl) {
    String url = StrUtil.blankToDefault(baseUrl, "https://api.openai.com/v1").replaceAll("/+$", "");
    return url.endsWith("/embeddings") ? url : url + "/embeddings";
  }

  private record IndexedVector(int index, float[] vector) {}
}
