package io.lexiloom.application.ai.gateway.llm;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.common.ErrorCode;
import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.exception.BusinessException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 原生兼容 Spring Framework 7.x / Spring Boot 4.x 的 OpenAI 协议兼容 ChatModel
 *
 * <p>完全实现 Spring AI 官方抽象契约 ({@link ChatModel})， 解决 Spring AI 1.0.0-M5 与 Spring 7 HttpHeaders
 * 之间历史二进制不兼容的问题。 支持 DeepSeek、通义千问 (Qwen)、OpenAI 等所有兼容标准 API。
 */
@Slf4j
public class Spring7OpenAiCompatibleChatModel implements ChatModel {

  private final String baseUrl;
  private final String apiKey;
  private final String defaultModel;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final ChatOptions defaultOptions;

  public Spring7OpenAiCompatibleChatModel(String baseUrl, String apiKey, String defaultModel) {
    this(baseUrl, apiKey, defaultModel, null, null);
  }

  public Spring7OpenAiCompatibleChatModel(
      String baseUrl,
      String apiKey,
      String defaultModel,
      HttpClient httpClient,
      ObjectMapper objectMapper) {
    this.baseUrl = normalizeEndpoint(baseUrl);
    this.apiKey = apiKey;
    this.defaultModel = StrUtil.blankToDefault(defaultModel, "deepseek-chat");
    this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    this.defaultOptions = ChatOptions.builder().model(this.defaultModel).build();

    if (httpClient != null) {
      this.httpClient = httpClient;
    } else {
      this.httpClient =
          HttpClient.newBuilder()
              .version(HttpClient.Version.HTTP_1_1)
              .connectTimeout(Duration.ofSeconds(10))
              .build();
    }
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    String effectiveKey = resolveEffectiveApiKey();
    if (StrUtil.isBlank(effectiveKey)) {
      throw new BusinessException(
          ErrorCode.Ai.LLM_API_ERROR, "未配置有效大模型 API Key（请配置系统模型环境变量或在 OpenAPI Key 中绑定自有 BYOK Key）");
    }

    try {
      String model = resolveModel(prompt);
      List<Map<String, Object>> messages = buildMessagePayload(prompt);

      Map<String, Object> body = new HashMap<>();
      body.put("model", model);
      body.put("messages", messages);
      body.put("stream", false);

      String jsonBody = objectMapper.writeValueAsString(body);

      log.info(
          "【Spring7OpenAiChatModel】发起同步大模型调用: endpoint={}, model={}, 消息数={}",
          this.baseUrl,
          model,
          messages.size());
      long start = System.currentTimeMillis();

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(this.baseUrl))
              .header("Authorization", "Bearer " + effectiveKey)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
              .timeout(Duration.ofSeconds(120))
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

      long latency = System.currentTimeMillis() - start;

      if (response.statusCode() >= 400) {
        log.error(
            "【Spring7OpenAiChatModel】请求失败: status={}, 耗时={}ms, body={}",
            response.statusCode(),
            latency,
            response.body());
        throw new BusinessException(
            ErrorCode.Ai.LLM_API_ERROR, "大模型 API 请求失败: HTTP " + response.statusCode());
      }

      JsonNode root = objectMapper.readTree(response.body());
      if (root.hasNonNull("error")) {
        throw new BusinessException(
            ErrorCode.Ai.LLM_API_ERROR, "大模型调用返回错误: " + root.get("error").toString());
      }

      Usage usage = null;
      JsonNode usageNode = root.get("usage");
      if (usageNode != null && !usageNode.isNull()) {
        Long promptTokens =
            usageNode.hasNonNull("prompt_tokens") ? usageNode.get("prompt_tokens").asLong() : null;
        Long completionTokens =
            usageNode.hasNonNull("completion_tokens")
                ? usageNode.get("completion_tokens").asLong()
                : null;
        Long totalTokens =
            usageNode.hasNonNull("total_tokens") ? usageNode.get("total_tokens").asLong() : null;
        usage = new DefaultUsage(promptTokens, completionTokens, totalTokens);
        log.info(
            "【Spring7OpenAiChatModel】大模型调用完成: model={}, 耗时={}ms, Token消耗=[prompt={}, completion={},"
                + " total={}]",
            model,
            latency,
            promptTokens,
            completionTokens,
            totalTokens);
      } else {
        log.info(
            "【Spring7OpenAiChatModel】大模型调用完成: model={}, 耗时={}ms (未返回 Token 消耗)", model, latency);
      }

      ChatResponseMetadata metadata =
          ChatResponseMetadata.builder()
              .withId(root.hasNonNull("id") ? root.get("id").asText() : null)
              .withModel(model)
              .withUsage(usage)
              .build();

      JsonNode choices = root.get("choices");
      if (choices == null || choices.isEmpty()) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(""))), metadata);
      }

      JsonNode firstChoice = choices.get(0);
      JsonNode messageNode = firstChoice.get("message");
      String content =
          messageNode != null && messageNode.hasNonNull("content")
              ? messageNode.get("content").asText()
              : "";
      String reasoning =
          messageNode != null && messageNode.hasNonNull("reasoning_content")
              ? messageNode.get("reasoning_content").asText()
              : null;

      if (StrUtil.isNotBlank(reasoning) && !content.contains("<think>")) {
        content = "<think>\n" + reasoning + "\n</think>\n" + content;
      }

      return new ChatResponse(List.of(new Generation(new AssistantMessage(content))), metadata);
    } catch (BusinessException be) {
      throw be;
    } catch (Exception e) {
      log.error(
          "【Spring7OpenAiChatModel】调用大模型异常: endpoint={}, err={}", this.baseUrl, e.getMessage(), e);
      throw new BusinessException(ErrorCode.Ai.LLM_API_ERROR, "大模型服务调用失败: " + e.getMessage());
    }
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    String effectiveKey = resolveEffectiveApiKey();
    if (StrUtil.isBlank(effectiveKey)) {
      return Flux.error(
          new BusinessException(
              ErrorCode.Ai.LLM_API_ERROR, "未配置有效大模型 API Key（请配置系统模型环境变量或在 OpenAPI Key 中绑定自有 BYOK Key）"));
    }

    return Flux.create(
        sink -> {
          try {
            String model = resolveModel(prompt);
            List<Map<String, Object>> messages = buildMessagePayload(prompt);

            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("stream", true);
            body.put("stream_options", Map.of("include_usage", true));

            String jsonBody = objectMapper.writeValueAsString(body);

            log.info(
                "【Spring7OpenAiChatModel】发起流式大模型连接: endpoint={}, model={}, 消息数={}",
                this.baseUrl,
                model,
                messages.size());
            long streamStart = System.currentTimeMillis();

            HttpRequest request =
                HttpRequest.newBuilder()
                    .uri(URI.create(this.baseUrl))
                    .header("Authorization", "Bearer " + effectiveKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(180))
                    .build();

            CompletableFuture<HttpResponse<Stream<String>>> future =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines());

            sink.onDispose(() -> future.cancel(true));

            future.whenComplete(
                (response, err) -> {
                  if (err != null) {
                    long latency = System.currentTimeMillis() - streamStart;
                    log.error(
                        "【Spring7OpenAiChatModel】流式连接异常: model={}, 耗时={}ms, err={}",
                        model,
                        latency,
                        err.getMessage());
                    sink.error(
                        new BusinessException(
                            ErrorCode.Ai.LLM_API_ERROR, "流式连接异常: " + err.getMessage()));
                    return;
                  }

                  if (response.statusCode() >= 400) {
                    long latency = System.currentTimeMillis() - streamStart;
                    log.error(
                        "【Spring7OpenAiChatModel】流式请求返回错误状态码: status={}, 耗时={}ms",
                        response.statusCode(),
                        latency);
                    sink.error(
                        new BusinessException(
                            ErrorCode.Ai.LLM_API_ERROR, "流式请求返回错误状态码: " + response.statusCode()));
                    return;
                  }

                  try (Stream<String> lines = response.body()) {
                    AtomicBoolean hasThinkingStarted = new AtomicBoolean(false);
                    AtomicReference<Usage> streamUsageRef = new AtomicReference<>(null);

                    lines.forEach(
                        line -> {
                          if (sink.isCancelled()) {
                            return;
                          }
                          String trimmed = line.trim();
                          if (trimmed.isEmpty() || trimmed.startsWith(":")) {
                            return;
                          }
                          if (trimmed.startsWith("data:")) {
                            String payload = trimmed.substring(5).trim();
                            if ("[DONE]".equalsIgnoreCase(payload)) {
                              if (hasThinkingStarted.get()) {
                                sink.next(
                                    new ChatResponse(
                                        List.of(new Generation(new AssistantMessage("</think>")))));
                                hasThinkingStarted.set(false);
                              }
                              return;
                            }
                            try {
                              JsonNode chunkNode = objectMapper.readTree(payload);

                              // 检查 SSE 块中是否包含 usage 统计信息
                              if (chunkNode.hasNonNull("usage")) {
                                JsonNode usageNode = chunkNode.get("usage");
                                Long promptTokens =
                                    usageNode.hasNonNull("prompt_tokens")
                                        ? usageNode.get("prompt_tokens").asLong()
                                        : null;
                                Long completionTokens =
                                    usageNode.hasNonNull("completion_tokens")
                                        ? usageNode.get("completion_tokens").asLong()
                                        : null;
                                Long totalTokens =
                                    usageNode.hasNonNull("total_tokens")
                                        ? usageNode.get("total_tokens").asLong()
                                        : null;
                                Usage u =
                                    new DefaultUsage(promptTokens, completionTokens, totalTokens);
                                streamUsageRef.set(u);
                                log.info(
                                    "【Spring7OpenAiChatModel】流式传输帧返回 Token 统计: model={},"
                                        + " Token消耗=[prompt={}, completion={}, total={}]",
                                    model,
                                    promptTokens,
                                    completionTokens,
                                    totalTokens);
                                ChatResponseMetadata metadata =
                                    ChatResponseMetadata.builder()
                                        .withModel(model)
                                        .withUsage(u)
                                        .build();
                                sink.next(
                                    new ChatResponse(
                                        List.of(new Generation(new AssistantMessage(""))),
                                        metadata));
                              }

                              JsonNode choicesNode = chunkNode.get("choices");
                              if (choicesNode != null && !choicesNode.isEmpty()) {
                                JsonNode deltaNode = choicesNode.get(0).get("delta");
                                if (deltaNode != null) {
                                  String reasoning =
                                      deltaNode.hasNonNull("reasoning_content")
                                          ? deltaNode.get("reasoning_content").asText()
                                          : null;
                                  String deltaContent =
                                      deltaNode.hasNonNull("content")
                                          ? deltaNode.get("content").asText()
                                          : null;

                                  if (StrUtil.isNotEmpty(reasoning)) {
                                    if (!hasThinkingStarted.get()) {
                                      sink.next(
                                          new ChatResponse(
                                              List.of(
                                                  new Generation(
                                                      new AssistantMessage("<think>")))));
                                      hasThinkingStarted.set(true);
                                    }
                                    sink.next(
                                        new ChatResponse(
                                            List.of(
                                                new Generation(new AssistantMessage(reasoning)))));
                                  }

                                  if (StrUtil.isNotEmpty(deltaContent)) {
                                    if (hasThinkingStarted.get()) {
                                      sink.next(
                                          new ChatResponse(
                                              List.of(
                                                  new Generation(
                                                      new AssistantMessage("</think>")))));
                                      hasThinkingStarted.set(false);
                                    }
                                    sink.next(
                                        new ChatResponse(
                                            List.of(
                                                new Generation(
                                                    new AssistantMessage(deltaContent)))));
                                  }
                                }
                              }
                            } catch (Exception parseEx) {
                              log.warn(
                                  "【Spring7OpenAiChatModel】解析 SSE 帧失败: {}, payload={}",
                                  parseEx.getMessage(),
                                  payload);
                            }
                          }
                        });

                    if (hasThinkingStarted.get()) {
                      sink.next(
                          new ChatResponse(
                              List.of(new Generation(new AssistantMessage("</think>")))));
                    }

                    long streamDuration = System.currentTimeMillis() - streamStart;
                    Usage finalUsage = streamUsageRef.get();
                    if (finalUsage != null) {
                      log.info(
                          "【Spring7OpenAiChatModel】流式传输完成: model={}, 总耗时={}ms,"
                              + " 最终Token消耗=[prompt={}, completion={}, total={}]",
                          model,
                          streamDuration,
                          finalUsage.getPromptTokens(),
                          finalUsage.getGenerationTokens(),
                          finalUsage.getTotalTokens());
                    } else {
                      log.info(
                          "【Spring7OpenAiChatModel】流式传输完成: model={}, 总耗时={}ms",
                          model,
                          streamDuration);
                    }

                    sink.complete();
                  } catch (Exception streamEx) {
                    sink.error(streamEx);
                  }
                });
          } catch (Exception e) {
            sink.error(e);
          }
        });
  }

  @Override
  public ChatOptions getDefaultOptions() {
    return this.defaultOptions;
  }

  private String resolveModel(Prompt prompt) {
    if (prompt != null && prompt.getOptions() != null) {
      String optModel = prompt.getOptions().getModel();
      if (StrUtil.isNotBlank(optModel)) {
        return optModel;
      }
    }
    return this.defaultModel;
  }

  private List<Map<String, Object>> buildMessagePayload(Prompt prompt) {
    List<Map<String, Object>> list = new ArrayList<>();
    if (prompt == null || prompt.getInstructions() == null) {
      return list;
    }

    for (Message message : prompt.getInstructions()) {
      String role =
          switch (message.getMessageType()) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
            case TOOL -> "tool";
            default -> "user";
          };
      list.add(Map.of("role", role, "content", StrUtil.nullToEmpty(message.getText())));
    }
    return list;
  }

  private String normalizeEndpoint(String url) {
    if (StrUtil.isBlank(url)) {
      return "https://api.deepseek.com/chat/completions";
    }
    String clean = url.trim().replaceAll("/+$", "");
    if (clean.endsWith("/chat/completions")) {
      return clean;
    }
    return clean + "/chat/completions";
  }

  private String resolveEffectiveApiKey() {
    var openApiPrincipal = SecurityHolder.getOpenApiPrincipal().orElse(null);
    if (openApiPrincipal != null && StrUtil.isNotBlank(openApiPrincipal.llmApiKey())) {
      return openApiPrincipal.llmApiKey().trim();
    }
    return StrUtil.isNotBlank(this.apiKey) ? this.apiKey.trim() : "";
  }
}
