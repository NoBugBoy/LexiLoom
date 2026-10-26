package io.lexiloom.application.ai.model.vo;

/** Sanitized summary of a server-side tool invocation, persisted with its assistant turn. */
public record ToolEventVO(String name, String status, String query, Integer resultCount) {}
