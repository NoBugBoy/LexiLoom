package io.lexiloom.application.ai.gateway.embedding;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.trace.RagTraceHolder;
import io.lexiloom.application.ai.trace.model.RagTraceContext;
import io.lexiloom.application.common.ErrorCode;
import io.lexiloom.application.exception.BusinessException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 原生兼容 Spring Framework 7.x / Spring Boot 4.x 的 OpenAI / DashScope 标准向量嵌入模型
 *
 * <p>完全遵照 Spring AI 官方抽象契约 ({@link org.springframework.ai.embedding.EmbeddingModel})， 解决 Spring AI
 * 1.0.0-M5 与 Spring 7 HttpHeaders 之间历史二进制不兼容的问题。
 */
@Slf4j
public class Spring7OpenAiCompatibleEmbeddingModel extends AbstractEmbeddingModel {

  private final String baseUrl;
  private final String apiKey;
  private final String model;
  private final int dimension;
  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  public Spring7OpenAiCompatibleEmbeddingModel(
      String baseUrl, String apiKey, String model, int dimension) {
    this(baseUrl, apiKey, model, dimension, null, null);
  }

  public Spring7OpenAiCompatibleEmbeddingModel(
      String baseUrl,
      String apiKey,
      String model,
      int dimension,
      RestClient restClient,
      ObjectMapper objectMapper) {
    this.baseUrl = normalizeEndpoint(baseUrl);
    this.apiKey = apiKey;
    this.model = StrUtil.blankToDefault(model, "text-embedding-v3");
    this.dimension = dimension > 0 ? dimension : 1536;
    this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();

    if (restClient != null) {
      this.restClient = restClient;
    } else {
      HttpClient httpClient =
          HttpClient.newBuilder()
              .version(HttpClient.Version.HTTP_1_1)
              .connectTimeout(Duration.ofSeconds(10))
              .build();
      JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
      factory.setReadTimeout(Duration.ofSeconds(60));
      this.restClient = RestClient.builder().requestFactory(factory).build();
    }
  }

  @Override
  public float[] embed(Document document) {
    if (document == null || StrUtil.isBlank(document.getFormattedContent(MetadataMode.NONE))) {
      return new float[this.dimension];
    }
    return embed(document.getFormattedContent(MetadataMode.NONE));
  }

  @Override
  public float[] embed(String text) {
    if (StrUtil.isBlank(text)) {
      return new float[this.dimension];
    }
    List<float[]> list = embed(List.of(text));
    return list.isEmpty() ? new float[this.dimension] : list.get(0);
  }

  /** 通义千问等模型硬性限制单次批量大小不得超过 20 (it should not be larger than 20) 采用保守安全的批次上限 16，彻底杜绝供应商参数超限 */
  private static final int MAX_BATCH_SIZE = 16;

  @Override
  public List<float[]> embed(List<String> texts) {
    if (texts == null || texts.isEmpty()) {
      return Collections.emptyList();
    }

    if (StrUtil.isBlank(apiKey)) {
      throw new BusinessException(ErrorCode.Ai.EMBEDDING_FAILED, "未配置 API Key，无法调用远程向量服务");
    }

    int totalChars = texts.stream().mapToInt(t -> t != null ? t.length() : 0).sum();
    log.info(
        "【Spring7OpenAiEmbeddingModel】开始调用向量模型: model={}, 条数={}, 总字符数={}",
        this.model,
        texts.size(),
        totalChars);
    long startTime = System.currentTimeMillis();

    try {
      if (texts.size() <= MAX_BATCH_SIZE) {
        BatchResult singleResult = doEmbedBatch(texts);
        long latency = System.currentTimeMillis() - startTime;
        if (singleResult.usage() != null) {
          if (singleResult.usage().getPromptTokens() != null
              && singleResult.usage().getPromptTokens() > 0) {
            RagTraceContext traceContext = RagTraceHolder.get();
            if (traceContext != null) {
              traceContext.recordEmbeddingTokens(singleResult.usage().getPromptTokens());
            }
          }
          log.info(
              "【Spring7OpenAiEmbeddingModel】向量化计算完成: model={}, 条数={}, 总耗时={}ms,"
                  + " 消耗Token=[prompt_tokens={}, total_tokens={}]",
              this.model,
              texts.size(),
              latency,
              singleResult.usage().getPromptTokens(),
              singleResult.usage().getTotalTokens());
        } else {
          log.info(
              "【Spring7OpenAiEmbeddingModel】向量化计算完成: model={}, 条数={}, 总耗时={}ms (未返回 Token 消耗)",
              this.model,
              texts.size(),
              latency);
        }
        return singleResult.vectors();
      }

      List<float[]> allVectors = new ArrayList<>(texts.size());
      long totalPromptTokens = 0;
      long totalTokens = 0;
      boolean hasUsage = false;

      for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
        int end = Math.min(i + MAX_BATCH_SIZE, texts.size());
        List<String> subList = texts.subList(i, end);
        BatchResult batchResult = doEmbedBatch(subList);
        allVectors.addAll(batchResult.vectors());
        if (batchResult.usage() != null) {
          hasUsage = true;
          if (batchResult.usage().getPromptTokens() != null) {
            totalPromptTokens += batchResult.usage().getPromptTokens();
          }
          if (batchResult.usage().getTotalTokens() != null) {
            totalTokens += batchResult.usage().getTotalTokens();
          }
        }
      }

      long latency = System.currentTimeMillis() - startTime;
      if (hasUsage) {
        if (totalPromptTokens > 0) {
          RagTraceContext traceContext = RagTraceHolder.get();
          if (traceContext != null) {
            traceContext.recordEmbeddingTokens(totalPromptTokens);
          }
        }
        log.info(
            "【Spring7OpenAiEmbeddingModel】分批向量化计算完成: model={}, 总条数={}, 分批数={}, 总耗时={}ms,"
                + " 累计Token消耗=[prompt_tokens={}, total_tokens={}]",
            this.model,
            texts.size(),
            (int) Math.ceil((double) texts.size() / MAX_BATCH_SIZE),
            latency,
            totalPromptTokens,
            totalTokens);
      } else {
        log.info(
            "【Spring7OpenAiEmbeddingModel】分批向量化计算完成: model={}, 总条数={}, 分批数={}, 总耗时={}ms",
            this.model,
            texts.size(),
            (int) Math.ceil((double) texts.size() / MAX_BATCH_SIZE),
            latency);
      }
      return allVectors;
    } catch (BusinessException be) {
      throw be;
    } catch (Exception e) {
      log.error(
          "【Spring7OpenAiEmbeddingModel】调用向量服务异常: endpoint={}, model={}, count={}, err={}",
          this.baseUrl,
          this.model,
          texts.size(),
          e.getMessage(),
          e);
      throw new BusinessException(ErrorCode.Ai.EMBEDDING_FAILED, "向量模型调用失败: " + e.getMessage());
    }
  }

  private BatchResult doEmbedBatch(List<String> batch) {
    long batchStart = System.currentTimeMillis();
    log.info(
        "【Spring7OpenAiEmbeddingModel】发起批次向量化请求: endpoint={}, model={}, 批次条数={}",
        this.baseUrl,
        this.model,
        batch.size());

    Map<String, Object> body =
        Map.of(
            "model", this.model,
            "input", batch,
            "dimensions", this.dimension);

    String responseJson =
        restClient
            .post()
            .uri(this.baseUrl)
            .header("Authorization", "Bearer " + this.apiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(String.class);

    long batchLatency = System.currentTimeMillis() - batchStart;
    BatchResult result = parseEmbeddingsAndUsageFromResponse(responseJson, batch.size());
    if (result.usage() != null) {
      log.info(
          "【Spring7OpenAiEmbeddingModel】批次向量化响应成功: model={}, 条数={}, 耗时={}ms,"
              + " Token消耗=[prompt_tokens={}, total_tokens={}]",
          this.model,
          batch.size(),
          batchLatency,
          result.usage().getPromptTokens(),
          result.usage().getTotalTokens());
    } else {
      log.info(
          "【Spring7OpenAiEmbeddingModel】批次向量化响应成功: model={}, 条数={}, 耗时={}ms (未返回 Token 消耗)",
          this.model,
          batch.size(),
          batchLatency);
    }
    return result;
  }

  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    if (request == null
        || request.getInstructions() == null
        || request.getInstructions().isEmpty()) {
      return new EmbeddingResponse(List.of());
    }
    List<String> inputs = request.getInstructions();
    List<float[]> vectors = embed(inputs);

    List<Embedding> embeddings = new ArrayList<>(vectors.size());
    for (int i = 0; i < vectors.size(); i++) {
      embeddings.add(new Embedding(vectors.get(i), i));
    }
    return new EmbeddingResponse(embeddings, new EmbeddingResponseMetadata(this.model, null));
  }

  @Override
  public int dimensions() {
    return this.dimension;
  }

  private BatchResult parseEmbeddingsAndUsageFromResponse(String responseJson, int expectedCount) {
    try {
      JsonNode root = objectMapper.readTree(responseJson);
      JsonNode dataNode = root.get("data");
      if (dataNode == null || !dataNode.isArray()) {
        log.warn("【Spring7OpenAiEmbeddingModel】响应未包含有效 data 向量数组: {}", responseJson);
        return new BatchResult(Collections.nCopies(expectedCount, new float[this.dimension]), null);
      }

      List<IndexedVector> list = new ArrayList<>(dataNode.size());
      for (JsonNode item : dataNode) {
        int index = item.hasNonNull("index") ? item.get("index").asInt() : list.size();
        JsonNode vecNode = item.get("embedding");
        if (vecNode != null && vecNode.isArray()) {
          float[] vec = new float[vecNode.size()];
          for (int j = 0; j < vecNode.size(); j++) {
            vec[j] = (float) vecNode.get(j).asDouble();
          }
          list.add(new IndexedVector(index, vec));
        }
      }

      list.sort(Comparator.comparingInt(IndexedVector::index));
      List<float[]> vectors = list.stream().map(IndexedVector::vector).toList();

      Usage usage = null;
      JsonNode usageNode = root.get("usage");
      if (usageNode != null && !usageNode.isNull()) {
        Long promptTokens =
            usageNode.hasNonNull("prompt_tokens") ? usageNode.get("prompt_tokens").asLong() : null;
        Long totalTokens =
            usageNode.hasNonNull("total_tokens") ? usageNode.get("total_tokens").asLong() : null;
        usage = new DefaultUsage(promptTokens, 0L, totalTokens);
      }

      return new BatchResult(vectors, usage);
    } catch (Exception e) {
      log.error("【Spring7OpenAiEmbeddingModel】解析响应向量 JSON 失败: {}", e.getMessage(), e);
      throw new BusinessException(ErrorCode.Ai.EMBEDDING_FAILED, "解析向量响应失败: " + e.getMessage());
    }
  }

  private String normalizeEndpoint(String url) {
    if (StrUtil.isBlank(url)) {
      return "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings";
    }
    String clean = url.trim().replaceAll("/+$", "");
    if (clean.endsWith("/embeddings")) {
      return clean;
    }
    return clean + "/embeddings";
  }

  private record IndexedVector(int index, float[] vector) {}

  private record BatchResult(List<float[]> vectors, Usage usage) {}
}
