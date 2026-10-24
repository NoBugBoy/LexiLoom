package io.lexiloom.application.user.api.dto;

import io.lexiloom.application.enums.AiLlmProviderEnum;
import io.lexiloom.application.enums.OpenApiKeyStatusEnum;
import java.time.LocalDateTime;
import org.jspecify.annotations.Nullable;

/**
 * OpenAPI 凭据跨模块业务传输 DTO
 */
public record OpenApiKeyDTO(
    Long id,
    Long userId,
    String name,
    String maskedKey,
    String scopes,
    String allowedWorkspaceIds,
    OpenApiKeyStatusEnum status,
    @Nullable LocalDateTime expiresAt,
    AiLlmProviderEnum llmProvider,
    @Nullable String llmApiKey) {}
