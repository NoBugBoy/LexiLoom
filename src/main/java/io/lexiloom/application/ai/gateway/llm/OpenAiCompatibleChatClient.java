package io.lexiloom.application.ai.gateway.llm;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.model.response.AiChatResponse;
import io.lexiloom.application.ai.trace.RagTraceHolder;
import io.lexiloom.application.ai.trace.model.RagTraceContext;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ChatMessage;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ToolCall;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ToolChatResult;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ToolDefinition;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 直接通过 OpenAI-compatible Chat Completions HTTP/SSE 协议调用模型。 */
@Slf4j
@Component
public class OpenAiCompatibleChatClient {

  private final HttpClient httpClient =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final ObjectMapper objectMapper;

  public OpenAiCompatibleChatClient(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public AiChatResponse chat(
      LlmChatCommand command, String baseUrl, String apiKey, String model) {
    String key = resolveApiKey(apiKey);
    long startedAt = System.currentTimeMillis();
    try {
      Map<String, Object> payload = buildPayload(command, model, false);
      HttpResponse<String> response =
          httpClient.send(
              request(baseUrl, key, payload, Duration.ofSeconds(120)),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      JsonNode root = requireSuccessfulResponse(response.statusCode(), response.body());
      JsonNode message = root.at("/choices/0/message");
      String content = text(message, "content");
      String reasoning = text(message, "reasoning_content");
      LlmUsage usage = readUsage(root.get("usage"));
      recordUsage(usage);
      ThinkingSplit split = splitThinking(content);
      String thinking = StrUtil.isNotBlank(reasoning) ? reasoning : split.thinking();
      String answer = StrUtil.isNotBlank(reasoning) ? split.content() : split.content();
      return AiChatResponse.of(
          answer,
          thinking,
          List.of(),
          System.currentTimeMillis() - startedAt,
          null,
          null,
          intValue(usage != null ? usage.promptTokens() : null),
          intValue(usage != null ? usage.completionTokens() : null),
          intValue(usage != null ? usage.totalTokens() : null));
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.Ai.LLM_API_ERROR, "大模型调用失败: " + e.getMessage());
    }
  }

  public ToolChatResult chatWithTools(
      LlmChatCommand command,
      List<ChatMessage> messages,
      List<ToolDefinition> tools,
      String baseUrl,
      String apiKey,
      String model) {
    String key = resolveApiKey(apiKey);
    try {
      Map<String, Object> payload = buildToolPayload(command, messages, tools, model);
      HttpResponse<String> response =
          httpClient.send(
              request(baseUrl, key, payload, Duration.ofSeconds(120)),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      JsonNode root = requireSuccessfulResponse(response.statusCode(), response.body());
      JsonNode message = root.at("/choices/0/message");
      LlmUsage usage = readUsage(root.get("usage"));
      recordUsage(usage);
      List<ToolCall> calls = new ArrayList<>();
      JsonNode toolCalls = message.get("tool_calls");
      if (toolCalls != null && toolCalls.isArray()) {
        for (JsonNode call : toolCalls) {
          JsonNode function = call.get("function");
          if (function == null) continue;
          calls.add(
              new ToolCall(
                  text(call, "id"),
                  text(function, "name"),
                  text(function, "arguments")));
        }
      }
      String content = text(message, "content");
      String reasoning = text(message, "reasoning_content");
      ThinkingSplit split = splitThinking(content);
      return new ToolChatResult(
          split.content(),
          StrUtil.isNotBlank(reasoning) ? reasoning : split.thinking(),
          calls,
          usage);
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.Ai.LLM_API_ERROR, "大模型工具调用失败: " + e.getMessage());
    }
  }

  public void stream(
      LlmChatCommand command,
      String baseUrl,
      String apiKey,
      String model,
      LlmStreamCallback callback) {
    String effectiveApiKey = resolveApiKey(apiKey);
    RagTraceContext trace = RagTraceHolder.get();
    Thread.startVirtualThread(
        () -> {
          try {
            if (trace == null) {
              streamBlocking(command, baseUrl, effectiveApiKey, model, callback);
            } else {
              ScopedValue.where(RagTraceHolder.CURRENT, trace)
                  .run(() -> streamBlocking(command, baseUrl, effectiveApiKey, model, callback));
            }
          } catch (Exception e) {
            callback.onError(e);
          }
        });
  }

  public void streamWithTools(
      LlmChatCommand command,
      List<ChatMessage> messages,
      List<ToolDefinition> tools,
      String baseUrl,
      String apiKey,
      String model,
      LlmStreamCallback callback) {
    String effectiveApiKey = resolveApiKey(apiKey);
    RagTraceContext trace = RagTraceHolder.get();
    Thread.startVirtualThread(
        () -> {
          Runnable task =
              () ->
                  streamWithToolsBlocking(
                      command, messages, tools, baseUrl, effectiveApiKey, model, callback);
          try {
            if (trace == null) task.run();
            else ScopedValue.where(RagTraceHolder.CURRENT, trace).run(task);
          } catch (Exception exception) {
            callback.onError(exception);
          }
        });
  }

  private void streamWithToolsBlocking(
      LlmChatCommand command,
      List<ChatMessage> messages,
      List<ToolDefinition> tools,
      String baseUrl,
      String apiKey,
      String model,
      LlmStreamCallback callback) {
    try {
      HttpResponse<Stream<String>> response =
          httpClient.send(
              request(
                  baseUrl,
                  apiKey,
                  buildToolPayload(command, messages, tools, model, true),
                  Duration.ofSeconds(180)),
              HttpResponse.BodyHandlers.ofLines());
      if (response.statusCode() >= 400) {
        throw new BusinessException(
            ErrorCode.Ai.LLM_API_ERROR, "流式模型工具请求失败: HTTP " + response.statusCode());
      }

      StringBuilder pending = new StringBuilder();
      StringBuilder toolArguments = new StringBuilder();
      List<StreamedToolCall> toolCalls = new ArrayList<>();
      AtomicBoolean inThinking = new AtomicBoolean(false);
      LlmUsage[] usage = new LlmUsage[1];
      try (Stream<String> lines = response.body()) {
        lines.filter(line -> line.startsWith("data:"))
            .map(line -> line.substring(5).trim())
            .filter(payload -> !payload.isEmpty() && !"[DONE]".equals(payload))
            .forEach(
                payload -> {
                  try {
                    JsonNode root = objectMapper.readTree(payload);
                    LlmUsage frameUsage = readUsage(root.get("usage"));
                    if (frameUsage != null) usage[0] = frameUsage;
                    JsonNode delta = root.at("/choices/0/delta");
                    String reasoning = text(delta, "reasoning_content");
                    String content = text(delta, "content");
                    if (!reasoning.isEmpty()) callback.onThinking(reasoning);
                    if (!content.isEmpty()) {
                      routeThinkingDelta(content, pending, inThinking, callback);
                    }
                    JsonNode calls = delta.get("tool_calls");
                    if (calls != null && calls.isArray()) {
                      for (JsonNode callDelta : calls) {
                        int index = callDelta.hasNonNull("index") ? callDelta.get("index").asInt() : 0;
                        while (toolCalls.size() <= index) toolCalls.add(new StreamedToolCall());
                        StreamedToolCall call = toolCalls.get(index);
                        String id = text(callDelta, "id");
                        if (!id.isEmpty()) call.id.append(id);
                        JsonNode function = callDelta.get("function");
                        if (function != null) {
                          String name = text(function, "name");
                          String args = text(function, "arguments");
                          if (!name.isEmpty()) call.name.append(name);
                          if (!args.isEmpty()) call.arguments.append(args);
                        }
                      }
                    }
                  } catch (Exception exception) {
                    throw new IllegalStateException("无法解析模型 SSE 数据帧", exception);
                  }
                });
      }
      if (!pending.isEmpty()) {
        emit(pending.toString(), inThinking.get(), callback);
      }
      recordUsage(usage[0]);
      List<ToolCall> completeCalls =
          toolCalls.stream()
              .map(call -> new ToolCall(call.id.toString(), call.name.toString(), call.arguments.toString()))
              .toList();
      if (!completeCalls.isEmpty()) callback.onToolCalls(completeCalls, usage[0]);
      else callback.onComplete(usage[0]);
    } catch (Exception exception) {
      callback.onError(exception);
    }
  }

  private static final class StreamedToolCall {
    private final StringBuilder id = new StringBuilder();
    private final StringBuilder name = new StringBuilder();
    private final StringBuilder arguments = new StringBuilder();
  }

  private void streamBlocking(
      LlmChatCommand command,
      String baseUrl,
      String apiKey,
      String model,
      LlmStreamCallback callback) {
    try {
      HttpResponse<Stream<String>> response =
          httpClient.send(
              request(
                  baseUrl,
                  resolveApiKey(apiKey),
                  buildPayload(command, model, true),
                  Duration.ofSeconds(180)),
              HttpResponse.BodyHandlers.ofLines());
      if (response.statusCode() >= 400) {
        throw new BusinessException(
            ErrorCode.Ai.LLM_API_ERROR, "流式模型请求失败: HTTP " + response.statusCode());
      }
      StringBuilder pending = new StringBuilder();
      AtomicBoolean inThinking = new AtomicBoolean(false);
      LlmUsage[] usage = new LlmUsage[1];
      try (Stream<String> lines = response.body()) {
        lines.filter(line -> line.startsWith("data:"))
            .map(line -> line.substring(5).trim())
            .filter(payload -> !payload.isEmpty() && !"[DONE]".equals(payload))
            .forEach(
                payload -> {
                  try {
                    JsonNode root = objectMapper.readTree(payload);
                    LlmUsage frameUsage = readUsage(root.get("usage"));
                    if (frameUsage != null) usage[0] = frameUsage;
                    JsonNode delta = root.at("/choices/0/delta");
                    String reasoning = text(delta, "reasoning_content");
                    String content = text(delta, "content");
                    if (StrUtil.isNotEmpty(reasoning)) callback.onThinking(reasoning);
                    if (StrUtil.isNotEmpty(content)) {
                      routeThinkingDelta(content, pending, inThinking, callback);
                    }
                  } catch (Exception e) {
                    throw new IllegalStateException("无法解析模型 SSE 数据帧", e);
                  }
                });
      }
      if (!pending.isEmpty()) {
        if (inThinking.get()) callback.onThinking(pending.toString());
        else callback.onMessage(pending.toString());
      }
      recordUsage(usage[0]);
      callback.onComplete(usage[0]);
    } catch (Exception e) {
      callback.onError(e);
    }
  }

  private HttpRequest request(String baseUrl, String apiKey, Map<String, Object> body, Duration timeout)
      throws Exception {
    return HttpRequest.newBuilder()
        .uri(URI.create(normalizeEndpoint(baseUrl)))
        .timeout(timeout)
        .header("Authorization", "Bearer " + apiKey)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
        .build();
  }

  private Map<String, Object> buildPayload(LlmChatCommand command, String model, boolean stream) {
    List<Map<String, String>> messages = new ArrayList<>();
    if (StrUtil.isNotBlank(command.systemPrompt())) {
      messages.add(Map.of("role", "system", "content", command.systemPrompt()));
    }
    command.history().forEach(
        message ->
            messages.add(
                Map.of(
                    "role", "assistant".equalsIgnoreCase(message.role()) ? "assistant" : "user",
                    "content", StrUtil.nullToEmpty(message.content()))));
    messages.add(Map.of("role", "user", "content", command.prompt()));
    Map<String, Object> body = new HashMap<>();
    body.put("model", model);
    body.put("messages", messages);
    body.put("stream", stream);
    body.put("temperature", command.temperature());
    if (command.maxTokens() != null) body.put("max_tokens", command.maxTokens());
    if (stream) body.put("stream_options", Map.of("include_usage", true));
    return body;
  }

  private Map<String, Object> buildToolPayload(
      LlmChatCommand command,
      List<ChatMessage> conversation,
      List<ToolDefinition> tools,
      String model) {
    return buildToolPayload(command, conversation, tools, model, false);
  }

  private Map<String, Object> buildToolPayload(
      LlmChatCommand command,
      List<ChatMessage> conversation,
      List<ToolDefinition> tools,
      String model,
      boolean stream) {
    List<Map<String, Object>> messages = new ArrayList<>();
    if (StrUtil.isNotBlank(command.systemPrompt())) {
      messages.add(Map.of("role", "system", "content", command.systemPrompt()));
    }
    for (ChatMessage message : conversation) {
      Map<String, Object> value = new HashMap<>();
      value.put("role", message.role());
      value.put("content", message.content() == null ? "" : message.content());
      if (message.toolCallId() != null) value.put("tool_call_id", message.toolCallId());
      if (!message.toolCalls().isEmpty()) {
        value.put(
            "tool_calls",
            message.toolCalls().stream()
                .map(
                    call ->
                        Map.of(
                            "id", call.id(),
                            "type", "function",
                            "function",
                                Map.of(
                                    "name", call.name(), "arguments", call.arguments())))
                .toList());
      }
      messages.add(value);
    }

    Map<String, Object> payload = new HashMap<>();
    payload.put("model", model);
    payload.put("messages", messages);
    payload.put("stream", stream);
    payload.put("temperature", command.temperature());
    if (command.maxTokens() != null) payload.put("max_tokens", command.maxTokens());
    if (stream) payload.put("stream_options", Map.of("include_usage", true));
    if (tools != null && !tools.isEmpty()) {
      payload.put(
          "tools",
          tools.stream()
              .map(
                  tool ->
                      Map.of(
                          "type",
                          "function",
                          "function",
                          Map.of(
                              "name", tool.name(),
                              "description", tool.description(),
                              "parameters", tool.parameters())))
              .toList());
      payload.put("tool_choice", "auto");
    }
    return payload;
  }

  private JsonNode requireSuccessfulResponse(int status, String body) throws Exception {
    if (status >= 400) {
      throw new BusinessException(ErrorCode.Ai.LLM_API_ERROR, "模型请求失败: HTTP " + status);
    }
    JsonNode root = objectMapper.readTree(body);
    if (root.hasNonNull("error")) {
      throw new BusinessException(ErrorCode.Ai.LLM_API_ERROR, "模型返回错误: " + root.get("error"));
    }
    return root;
  }

  private void routeThinkingDelta(
      String delta,
      StringBuilder pending,
      AtomicBoolean inThinking,
      LlmStreamCallback callback) {
    pending.append(delta);
    while (!pending.isEmpty()) {
      String value = pending.toString();
      int start = value.indexOf("<think>");
      int end = value.indexOf("</think>");
      if (start >= 0) {
        if (start > 0) {
          emit(value.substring(0, start), inThinking.get(), callback);
        }
        pending.setLength(0);
        pending.append(value.substring(start + 7));
        inThinking.set(true);
        continue;
      }
      if (end >= 0) {
        if (end > 0) {
          emit(value.substring(0, end), inThinking.get(), callback);
        }
        pending.setLength(0);
        pending.append(value.substring(end + 8));
        inThinking.set(false);
        continue;
      }
      int hold = Math.max(0, Math.max("<think>".length(), "</think>".length()) - 1);
      if (value.length() <= hold) return;
      emit(value.substring(0, value.length() - hold), inThinking.get(), callback);
      pending.setLength(0);
      pending.append(value.substring(value.length() - hold));
      return;
    }
  }

  private void emit(String text, boolean thinking, LlmStreamCallback callback) {
    if (thinking) callback.onThinking(text);
    else callback.onMessage(text);
  }

  private ThinkingSplit splitThinking(String content) {
    int start = content.indexOf("<think>");
    int end = content.indexOf("</think>");
    if (start < 0 || end < start) return new ThinkingSplit(content, null);
    return new ThinkingSplit(
        content.substring(0, start) + content.substring(end + 8),
        content.substring(start + 7, end).strip());
  }

  private LlmUsage readUsage(JsonNode node) {
    if (node == null || node.isNull()) return null;
    return new LlmUsage(number(node, "prompt_tokens"), number(node, "completion_tokens"), number(node, "total_tokens"));
  }

  private void recordUsage(LlmUsage usage) {
    RagTraceContext context = RagTraceHolder.get();
    if (usage != null && context != null) context.recordLlmTokens(usage.promptTokens(), usage.completionTokens());
  }

  private Long number(JsonNode node, String field) {
    return node.hasNonNull(field) ? node.get(field).asLong() : null;
  }

  private String text(JsonNode node, String field) {
    return node != null && node.hasNonNull(field) ? node.get(field).asString() : "";
  }

  private Integer intValue(Long value) {
    return value != null ? Math.toIntExact(value) : null;
  }

  private String normalizeEndpoint(String baseUrl) {
    String value = StrUtil.blankToDefault(baseUrl, "https://api.deepseek.com").replaceAll("/+$", "");
    return value.endsWith("/chat/completions") ? value : value + "/chat/completions";
  }

  private String resolveApiKey(String configuredKey) {
    String byok = SecurityHolder.getOpenApiPrincipal().map(p -> p.llmApiKey()).orElse(null);
    String key = StrUtil.blankToDefault(byok, configuredKey);
    if (StrUtil.isBlank(key)) {
      throw new BusinessException(ErrorCode.Ai.LLM_API_ERROR, "未配置有效大模型 API Key");
    }
    return key.trim();
  }

  private record ThinkingSplit(String content, String thinking) {}
}
