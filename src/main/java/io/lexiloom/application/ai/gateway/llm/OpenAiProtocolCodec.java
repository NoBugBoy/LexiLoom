package io.lexiloom.application.ai.gateway.llm;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ChatMessage;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ToolDefinition;
import io.lexiloom.application.exception.ErrorCode;
import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.exception.BusinessException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * OpenAI-Compatible Chat Completions 协议载荷编解码与思维链标签路由器 (OpenAiProtocolCodec)
 *
 * <p>集中负责请求报文组装、{@code <think>} 标签流式/同步切分、Token 用量提取及 Endpoint/API Key 归一化。
 */
final class OpenAiProtocolCodec {

  private OpenAiProtocolCodec() {}

  static Map<String, Object> buildPayload(LlmChatCommand command, String model, boolean stream) {
    List<Map<String, String>> messages = new ArrayList<>();
    if (StrUtil.isNotBlank(command.systemPrompt())) {
      messages.add(Map.of("role", "system", "content", command.systemPrompt()));
    }
    command
        .history()
        .forEach(
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
    if (command.maxTokens() != null) {
      body.put("max_tokens", command.maxTokens());
    }
    if (stream) {
      body.put("stream_options", Map.of("include_usage", true));
    }
    return body;
  }

  static Map<String, Object> buildToolPayload(
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
      if (message.toolCallId() != null) {
        value.put("tool_call_id", message.toolCallId());
      }
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
                                Map.of("name", call.name(), "arguments", call.arguments())))
                .toList());
      }
      messages.add(value);
    }

    Map<String, Object> payload = new HashMap<>();
    payload.put("model", model);
    payload.put("messages", messages);
    payload.put("stream", stream);
    payload.put("temperature", command.temperature());
    if (command.maxTokens() != null) {
      payload.put("max_tokens", command.maxTokens());
    }
    if (stream) {
      payload.put("stream_options", Map.of("include_usage", true));
    }
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

  static void routeThinkingDelta(
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
      if (value.length() <= hold) {
        return;
      }
      emit(value.substring(0, value.length() - hold), inThinking.get(), callback);
      pending.setLength(0);
      pending.append(value.substring(value.length() - hold));
      return;
    }
  }

  static void emit(String text, boolean thinking, LlmStreamCallback callback) {
    if (thinking) {
      callback.onThinking(text);
    } else {
      callback.onMessage(text);
    }
  }

  static ThinkingSplit splitThinking(String content) {
    int start = content.indexOf("<think>");
    int end = content.indexOf("</think>");
    if (start < 0 || end < start) {
      return new ThinkingSplit(content, null);
    }
    return new ThinkingSplit(
        content.substring(0, start) + content.substring(end + 8),
        content.substring(start + 7, end).strip());
  }

  @Nullable
  static LlmUsage readUsage(@Nullable JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    return new LlmUsage(
        number(node, "prompt_tokens"),
        number(node, "completion_tokens"),
        number(node, "total_tokens"));
  }

  @Nullable
  static Long number(JsonNode node, String field) {
    return node.hasNonNull(field) ? node.get(field).asLong() : null;
  }

  static String text(@Nullable JsonNode node, String field) {
    return node != null && node.hasNonNull(field) ? node.get(field).asString() : "";
  }

  @Nullable
  static Integer intValue(@Nullable Long value) {
    return value != null ? Math.toIntExact(value) : null;
  }

  static String normalizeEndpoint(String baseUrl) {
    String value =
        StrUtil.blankToDefault(baseUrl, "https://api.deepseek.com").replaceAll("/+$", "");
    return value.endsWith("/chat/completions") ? value : value + "/chat/completions";
  }

  static String resolveApiKey(String configuredKey) {
    String byok = SecurityHolder.getOpenApiPrincipal().map(p -> p.llmApiKey()).orElse(null);
    String key = StrUtil.blankToDefault(byok, configuredKey);
    if (StrUtil.isBlank(key)) {
      throw new BusinessException(ErrorCode.Ai.LLM_API_ERROR, "未配置有效大模型 API Key");
    }
    return key.trim();
  }

  record ThinkingSplit(String content, @Nullable String thinking) {}
}
