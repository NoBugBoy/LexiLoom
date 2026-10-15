package io.lexiloom.application.ai.gateway.rerank;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.enums.RerankStrategyEnum;
import io.lexiloom.application.ai.model.search.DocChunkDocument;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 深度 Cross-Encoder / 通义千问 DashScope Rerank 模型重排适配器 (CrossEncoderRerankAdapter)
 *
 * <p>基于通义千问官方 Text-Rerank API 规范 (如 {@code qwen3.7-text-rerank}) 实现真实深度重排。
 */
@Slf4j
@Component
public class CrossEncoderRerankAdapter implements RerankGateway {

  public static final String DEFAULT_DASHSCOPE_RERANK_URL =
      "https://maas.qianwenaiapi.com/api/v1/services/rerank/text-rerank/text-rerank";
  public static final String DEFAULT_MODEL = "qwen3.7-text-rerank";

  private final AiProperties aiProperties;
  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  @org.springframework.beans.factory.annotation.Autowired
  public CrossEncoderRerankAdapter(AiProperties aiProperties) {
    this(aiProperties, null, null);
  }

  public CrossEncoderRerankAdapter(
      AiProperties aiProperties,
      @Nullable RestClient restClient,
      @Nullable ObjectMapper objectMapper) {
    this.aiProperties = aiProperties;
    this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();

    if (restClient != null) {
      this.restClient = restClient;
    } else {
      HttpClient httpClient =
          HttpClient.newBuilder()
              .version(HttpClient.Version.HTTP_1_1)
              .connectTimeout(Duration.ofSeconds(5))
              .build();
      JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
      factory.setReadTimeout(Duration.ofSeconds(15));
      this.restClient = RestClient.builder().requestFactory(factory).build();
    }
  }

  @Override
  public List<ScoredDocChunkVO> rerank(RerankCommand command) {
    if (command == null || command.candidates().isEmpty()) {
      return List.of();
    }

    String apiKey = resolveApiKey();
    if (StrUtil.isBlank(apiKey)) {
      throw new IllegalStateException(
          "启用 CROSS_ENCODER 重排必须配置有效 API Key (ai.rerank.api-key 或环境变量 DASHSCOPE_API_KEY)");
    }

    try {
      List<ScoredDocChunkVO> remoteResults = invokeRemoteRerank(command, apiKey);
      log.info(
          "【CrossEncoderRerankAdapter】DashScope Rerank 远程服务精排成功: query=[{}], 输入切片数={}, 输出切片数={}",
          command.query(),
          command.candidates().size(),
          remoteResults.size());
      return remoteResults;
    } catch (Exception e) {
      log.error(
          "【CrossEncoderRerankAdapter】调用远程 DashScope Rerank 服务异常: query=[{}], error={}",
          command.query(),
          e.getMessage());
      throw new IllegalStateException("调用 DashScope Rerank 服务失败: " + e.getMessage(), e);
    }
  }

  @Override
  public boolean isEnabled() {
    return aiProperties != null
        && aiProperties.getRerank() != null
        && aiProperties.getRerank().isEnabled()
        && aiProperties.getRerank().getStrategy() == RerankStrategyEnum.CROSS_ENCODER;
  }

  @Override
  public RerankStrategyEnum getStrategy() {
    return RerankStrategyEnum.CROSS_ENCODER;
  }

  private String resolveApiKey() {
    if (aiProperties != null && aiProperties.getRerank() != null) {
      String key = aiProperties.getRerank().getApiKey();
      if (StrUtil.isNotBlank(key)) {
        return key.trim();
      }
    }
    String envKey = System.getenv("DASHSCOPE_API_KEY");
    return StrUtil.isNotBlank(envKey) ? envKey.trim() : null;
  }

  private String resolveEndpoint() {
    if (aiProperties != null
        && aiProperties.getRerank() != null
        && StrUtil.isNotBlank(aiProperties.getRerank().getBaseUrl())) {
      return aiProperties.getRerank().getBaseUrl().trim();
    }
    return DEFAULT_DASHSCOPE_RERANK_URL;
  }

  private String resolveModel() {
    if (aiProperties != null
        && aiProperties.getRerank() != null
        && StrUtil.isNotBlank(aiProperties.getRerank().getModel())) {
      return aiProperties.getRerank().getModel().trim();
    }
    return DEFAULT_MODEL;
  }

  /** 调用 DashScope / Qianwen 官方 Text-Rerank API */
  private List<ScoredDocChunkVO> invokeRemoteRerank(RerankCommand command, String apiKey)
      throws Exception {
    String endpoint = resolveEndpoint();
    String model = resolveModel();
    List<DocChunkDocument> candidateList = command.candidates();
    if (candidateList.size() > 500) {
      log.warn("【CrossEncoderRerankAdapter】候选切片数量超过 DashScope 单次上限(500)，防御性截取前 500 条");
      candidateList = candidateList.subList(0, 500);
    }

    List<String> docTexts = new ArrayList<>(candidateList.size());
    for (DocChunkDocument doc : candidateList) {
      docTexts.add(buildRerankDocumentText(doc));
    }

    int topN = Math.max(1, Math.min(command.topK(), candidateList.size()));
    String query = command.query() != null ? command.query() : "";

    Map<String, Object> requestPayload;
    if (isOpenAiCompatibleEndpoint(endpoint)) {
      // 兼容 OpenAI 标准 / compatible-api/v1/reranks 格式
      requestPayload =
          Map.of(
              "model", model,
              "query", query,
              "documents", docTexts,
              "top_n", topN);
    } else {
      // 通义千问 DashScope 官方 text-rerank 嵌套格式
      Map<String, Object> input =
          Map.of(
              "query", query,
              "documents", docTexts);
      Map<String, Object> parameters = Map.of("top_n", topN, "return_documents", false);
      requestPayload =
          Map.of(
              "model", model,
              "input", input,
              "parameters", parameters);
    }

    String requestBodyJson = objectMapper.writeValueAsString(requestPayload);

    String responseBody =
        restClient
            .post()
            .uri(endpoint)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(requestBodyJson)
            .retrieve()
            .body(String.class);

    if (StrUtil.isBlank(responseBody)) {
      return List.of();
    }

    return parseRerankResponse(responseBody, candidateList, command.topK());
  }

  private boolean isOpenAiCompatibleEndpoint(String endpoint) {
    if (StrUtil.isBlank(endpoint)) {
      return false;
    }
    String lower = endpoint.toLowerCase();
    return lower.contains("/compatible-api/")
        || lower.endsWith("/reranks")
        || (lower.endsWith("/rerank") && !lower.contains("text-rerank"));
  }

  /** 解析通义千问 / DashScope Rerank 响应 (兼容 output.results 与顶级 results 两种格式) */
  private List<ScoredDocChunkVO> parseRerankResponse(
      String responseBody, List<DocChunkDocument> candidates, int topK) throws Exception {
    JsonNode root = objectMapper.readTree(responseBody);
    JsonNode resultsNode = null;
    if (root.has("output") && root.get("output").has("results")) {
      resultsNode = root.get("output").get("results");
    } else if (root.has("results")) {
      resultsNode = root.get("results");
    }

    if (resultsNode == null || !resultsNode.isArray() || resultsNode.isEmpty()) {
      return List.of();
    }

    List<ScoredDocChunkVO> scoredList = new ArrayList<>();
    for (int rank = 0; rank < resultsNode.size(); rank++) {
      JsonNode item = resultsNode.get(rank);
      int originalIndex = item.path("index").asInt(-1);
      double score = item.path("relevance_score").asDouble(0.0);

      if (originalIndex >= 0 && originalIndex < candidates.size()) {
        DocChunkDocument doc = candidates.get(originalIndex);
        scoredList.add(ScoredDocChunkVO.of(doc, score, rank + 1));
      }
    }

    return scoredList.stream()
        .sorted(Comparator.comparingDouble(ScoredDocChunkVO::score).reversed())
        .limit(topK)
        .toList();
  }

  /**
   * 构造送往 Rerank 模型的切片完整表征文本
   *
   * <p>必须携带上下文帽子（[文档: 《...》] > [章节: ...] > [类型: ...]），使 Cross-Encoder 能够感知 该切片归属的文档名称、业务系统标识（如
   * S16）与章节/表格类型，避免纯表格正文因缺少系统名而在重排时被误判为无关。
   */
  private String buildRerankDocumentText(DocChunkDocument doc) {
    if (doc == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    if (StrUtil.isNotBlank(doc.getContextPrefix())) {
      sb.append(doc.getContextPrefix().trim()).append("\n\n");
    } else if (StrUtil.isNotBlank(doc.getTitle())) {
      sb.append("[文档: 《").append(doc.getTitle().trim()).append("》]\n\n");
    }
    if (StrUtil.isNotBlank(doc.getContent())) {
      sb.append(doc.getContent().trim());
    }
    String text = sb.toString().trim();
    if (StrUtil.isBlank(text) && StrUtil.isNotBlank(doc.getTitle())) {
      text = doc.getTitle().trim();
    }
    // 防御性单文档文本截断 (防止单个异常大切片超出 Token 限制导致 API 报 400 错误)
    if (text.length() > 30000) {
      text = text.substring(0, 30000);
    }
    return text;
  }
}
