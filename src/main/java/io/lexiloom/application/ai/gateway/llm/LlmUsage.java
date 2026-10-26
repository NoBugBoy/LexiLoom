package io.lexiloom.application.ai.gateway.llm;

/** Token 使用量由 OpenAI-compatible API 返回，字段缺失时保留为 null。 */
public record LlmUsage(Long promptTokens, Long completionTokens, Long totalTokens) {}
