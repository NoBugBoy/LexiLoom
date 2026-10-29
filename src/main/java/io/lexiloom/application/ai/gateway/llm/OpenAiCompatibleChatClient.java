package io.lexiloom.application.ai.gateway.llm;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ChatMessage;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ToolCall;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ToolChatResult;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ToolDefinition;
import io.lexiloom.application.ai.gateway.llm.OpenAiProtocolCodec.ThinkingSplit;
import io.lexiloom.application.ai.model.response.AiChatResponse;
import io.lexiloom.application.ai.trace.RagTraceHolder;
import io.lexiloom.application.ai.trace.model.RagTraceContext;
import io.lexiloom.application.exception.ErrorCode;
import io.lexiloom.application.exception.BusinessException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
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

  public AiChatResponse chat(LlmChatCommand command, String baseUrl, String apiKey, String model) {
    String key = OpenAiProtocolCodec.resolveApiKey(apiKey);
    long startedAt = System.currentTimeMillis();
    try {
      Map<String, Object> payload = OpenAiProtocolCodec.buildPayload(command, model, false);
      HttpResponse<String> response =
          httpClient.send(
              request(baseUrl, key, payload, Duration.ofSeconds(120)),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      JsonNode root = requireSuccessfulResponse(response.statusCode(), response.body());
      JsonNode message = root.at("/choices/0/message");
      String content = OpenAiProtocolCodec.text(message, "content");
      String reasoning = OpenAiProtocolCodec.text(message, "reasoning_content");
      LlmUsage usage = OpenAiProtocolCodec.readUsage(root.get("usage"));
      recordUsage(usage);
      ThinkingSplit split = OpenAiProtocolCodec.splitThinking(content);
      String thinking = StrUtil.isNotBlank(reasoning) ? reasoning : split.thinking();
      String answer = split.content();
      return AiChatResponse.of(
          answer,
          thinking,
          List.of(),
          System.currentTimeMillis() - startedAt,
          null,
          null,
          OpenAiProtocolCodec.intValue(usage != null ? usage.promptTokens() : null),
          OpenAiProtocolCodec.intValue(usage != null ? usage.completionTokens() : null),
          OpenAiProtocolCodec.intValue(usage != null ? usage.totalTokens() : null));
    } catch (BusinessException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(ErrorCode.Ai.LLM_API_ERROR, "大模型调用被中断: " + e.getMessage());
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
    String key = OpenAiProtocolCodec.resolveApiKey(apiKey);
    try {
      Map<String, Object> payload =
          OpenAiProtocolCodec.buildToolPayload(command, messages, tools, model, false);
      HttpResponse<String> response =
          httpClient.send(
              request(baseUrl, key, payload, Duration.ofSeconds(120)),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      JsonNode root = requireSuccessfulResponse(response.statusCode(), response.body());
      JsonNode message = root.at("/choices/0/message");
      LlmUsage usage = OpenAiProtocolCodec.readUsage(root.get("usage"));
      recordUsage(usage);
      List<ToolCall> calls = new ArrayList<>();
      JsonNode toolCalls = message.get("tool_calls");
      if (toolCalls != null && toolCalls.isArray()) {
        for (JsonNode call : toolCalls) {
          JsonNode function = call.get("function");
          if (function == null) {
            continue;
          }
          calls.add(
              new ToolCall(
                  OpenAiProtocolCodec.text(call, "id"),
                  OpenAiProtocolCodec.text(function, "name"),
                  OpenAiProtocolCodec.text(function, "arguments")));
        }
      }
      String content = OpenAiProtocolCodec.text(message, "content");
      String reasoning = OpenAiProtocolCodec.text(message, "reasoning_content");
      ThinkingSplit split = OpenAiProtocolCodec.splitThinking(content);
      return new ToolChatResult(
          split.content(),
          StrUtil.isNotBlank(reasoning) ? reasoning : split.thinking(),
          calls,
          usage);
    } catch (BusinessException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(ErrorCode.Ai.LLM_API_ERROR, "大模型工具调用被中断: " + e.getMessage());
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
    String effectiveApiKey = OpenAiProtocolCodec.resolveApiKey(apiKey);
    startStreamingVirtualThread(
        () -> streamBlocking(command, baseUrl, effectiveApiKey, model, callback), callback);
  }

  public void streamWithTools(
      LlmChatCommand command,
      List<ChatMessage> messages,
      List<ToolDefinition> tools,
      String baseUrl,
      String apiKey,
      String model,
      LlmStreamCallback callback) {
    String effectiveApiKey = OpenAiProtocolCodec.resolveApiKey(apiKey);
    startStreamingVirtualThread(
        () ->
            streamWithToolsBlocking(
                command, messages, tools, baseUrl, effectiveApiKey, model, callback),
        callback);
  }

  private void startStreamingVirtualThread(Runnable task, LlmStreamCallback callback) {
    RagTraceContext trace = RagTraceHolder.get();
    SecurityContext securityContext = SecurityContextHolder.getContext();
    Thread.startVirtualThread(
        () -> {
          SecurityContext previous = SecurityContextHolder.getContext();
          try {
            SecurityContextHolder.setContext(securityContext);
            if (trace == null) {
              task.run();
            } else {
              ScopedValue.where(RagTraceHolder.CURRENT, trace).run(task);
            }
          } catch (Exception exception) {
            callback.onError(exception);
          } finally {
            SecurityContextHolder.setContext(previous);
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
                  OpenAiProtocolCodec.buildToolPayload(command, messages, tools, model, true),
                  Duration.ofSeconds(180)),
              HttpResponse.BodyHandlers.ofLines());
      if (response.statusCode() >= 400) {
        throw new BusinessException(
            ErrorCode.Ai.LLM_API_ERROR, "流式模型工具请求失败: HTTP " + response.statusCode());
      }

      StringBuilder pending = new StringBuilder();
      List<StreamedToolCall> toolCalls = new ArrayList<>();
      AtomicBoolean inThinking = new AtomicBoolean(false);
      LlmUsage[] usage = new LlmUsage[1];
      try (Stream<String> lines = response.body()) {
        lines
            .filter(line -> line.startsWith("data:"))
            .map(line -> line.substring(5).trim())
            .filter(payload -> !payload.isEmpty() && !"[DONE]".equals(payload))
            .forEach(
                payload -> {
                  try {
                    JsonNode root = objectMapper.readTree(payload);
                    LlmUsage frameUsage = OpenAiProtocolCodec.readUsage(root.get("usage"));
                    if (frameUsage != null) {
                      usage[0] = frameUsage;
                    }
                    JsonNode delta = root.at("/choices/0/delta");
                    String reasoning = OpenAiProtocolCodec.text(delta, "reasoning_content");
                    String content = OpenAiProtocolCodec.text(delta, "content");
                    if (!reasoning.isEmpty()) {
                      callback.onThinking(reasoning);
                    }
                    if (!content.isEmpty()) {
                      OpenAiProtocolCodec.routeThinkingDelta(
                          content, pending, inThinking, callback);
                    }
                    JsonNode calls = delta.get("tool_calls");
                    if (calls != null && calls.isArray()) {
                      for (JsonNode callDelta : calls) {
                        int index =
                            callDelta.hasNonNull("index") ? callDelta.get("index").asInt() : 0;
                        while (toolCalls.size() <= index) {
                          toolCalls.add(new StreamedToolCall());
                        }
                        StreamedToolCall call = toolCalls.get(index);
                        String id = OpenAiProtocolCodec.text(callDelta, "id");
                        if (!id.isEmpty()) {
                          call.id.append(id);
                        }
                        JsonNode function = callDelta.get("function");
                        if (function != null) {
                          String name = OpenAiProtocolCodec.text(function, "name");
                          String args = OpenAiProtocolCodec.text(function, "arguments");
                          if (!name.isEmpty()) {
                            call.name.append(name);
                          }
                          if (!args.isEmpty()) {
                            call.arguments.append(args);
                          }
                        }
                      }
                    }
                  } catch (Exception exception) {
                    throw new IllegalStateException("无法解析模型 SSE 数据帧", exception);
                  }
                });
      }
      if (!pending.isEmpty()) {
        OpenAiProtocolCodec.emit(pending.toString(), inThinking.get(), callback);
      }
      recordUsage(usage[0]);
      List<ToolCall> completeCalls =
          toolCalls.stream()
              .map(
                  call ->
                      new ToolCall(
                          call.id.toString(), call.name.toString(), call.arguments.toString()))
              .toList();
      if (!completeCalls.isEmpty()) {
        callback.onToolCalls(completeCalls, usage[0]);
      } else {
        callback.onComplete(usage[0]);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      callback.onError(exception);
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
                  OpenAiProtocolCodec.resolveApiKey(apiKey),
                  OpenAiProtocolCodec.buildPayload(command, model, true),
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
        lines
            .filter(line -> line.startsWith("data:"))
            .map(line -> line.substring(5).trim())
            .filter(payload -> !payload.isEmpty() && !"[DONE]".equals(payload))
            .forEach(
                payload -> {
                  try {
                    JsonNode root = objectMapper.readTree(payload);
                    LlmUsage frameUsage = OpenAiProtocolCodec.readUsage(root.get("usage"));
                    if (frameUsage != null) {
                      usage[0] = frameUsage;
                    }
                    JsonNode delta = root.at("/choices/0/delta");
                    String reasoning = OpenAiProtocolCodec.text(delta, "reasoning_content");
                    String content = OpenAiProtocolCodec.text(delta, "content");
                    if (StrUtil.isNotEmpty(reasoning)) {
                      callback.onThinking(reasoning);
                    }
                    if (StrUtil.isNotEmpty(content)) {
                      OpenAiProtocolCodec.routeThinkingDelta(
                          content, pending, inThinking, callback);
                    }
                  } catch (Exception e) {
                    throw new IllegalStateException("无法解析模型 SSE 数据帧", e);
                  }
                });
      }
      if (!pending.isEmpty()) {
        OpenAiProtocolCodec.emit(pending.toString(), inThinking.get(), callback);
      }
      recordUsage(usage[0]);
      callback.onComplete(usage[0]);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      callback.onError(e);
    } catch (Exception e) {
      callback.onError(e);
    }
  }

  private HttpRequest request(
      String baseUrl, String apiKey, Map<String, Object> body, Duration timeout) throws Exception {
    return HttpRequest.newBuilder()
        .uri(URI.create(OpenAiProtocolCodec.normalizeEndpoint(baseUrl)))
        .timeout(timeout)
        .header("Authorization", "Bearer " + apiKey)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
        .build();
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

  private void recordUsage(@Nullable LlmUsage usage) {
    RagTraceContext context = RagTraceHolder.get();
    if (usage != null && context != null) {
      context.recordLlmTokens(usage.promptTokens(), usage.completionTokens());
    }
  }
}
