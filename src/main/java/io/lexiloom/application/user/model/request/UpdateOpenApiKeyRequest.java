package io.lexiloom.application.user.model.request;

import io.lexiloom.application.enums.AiLlmProviderEnum;
import io.lexiloom.application.enums.OpenApiKeyStatusEnum;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * 更新 OpenAPI 凭据配置请求体
 */
public record UpdateOpenApiKeyRequest(
    @Nullable @Size(max = 64, message = "密钥名称长度不能超过64字符") String name,
    @Nullable String scopes,
    @Nullable String allowedWorkspaceIds,
    @Nullable OpenApiKeyStatusEnum status,
    @Nullable Integer expireDays,
    @Nullable AiLlmProviderEnum llmProvider,
    @Nullable String llmApiKey) {}
