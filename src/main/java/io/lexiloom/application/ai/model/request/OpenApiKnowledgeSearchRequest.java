package io.lexiloom.application.ai.model.request;

import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.Nullable;

/**
 * OpenAPI 知识库切片检索请求体
 */
public record OpenApiKnowledgeSearchRequest(
    @NotBlank(message = "检索内容 q 不能为空") String q,
    @Nullable Long workspaceId,
    @Nullable Integer topK,
    @Nullable Double minScore) {}
