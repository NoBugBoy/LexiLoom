package io.lexiloom.application.user.model.request;

import io.lexiloom.application.enums.AiLlmProviderEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * 创建 OpenAPI 凭据请求体
 */
public record CreateOpenApiKeyRequest(
    @NotBlank(message = "密钥名称不能为空")
    @Size(max = 64, message = "密钥名称长度不能超过64字符")
    String name,
    @Nullable String scopes,
    @Nullable String allowedWorkspaceIds,
    @Nullable Integer expireDays,
    @Nullable AiLlmProviderEnum llmProvider,
    @Nullable String llmApiKey) {}
