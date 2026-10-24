package io.lexiloom.application.user.model.vo;

import cn.hutool.core.date.DatePattern;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.lexiloom.application.enums.AiLlmProviderEnum;
import io.lexiloom.application.enums.OpenApiKeyStatusEnum;
import java.time.LocalDateTime;

/**
 * OpenAPI 凭据视图展示模型 (VO)
 */
public record OpenApiKeyVO(
    Long id,
    Long userId,
    String name,
    String maskedKey,
    String scopes,
    String allowedWorkspaceIds,
    OpenApiKeyStatusEnum status,
    @JsonFormat(pattern = DatePattern.NORM_DATETIME_PATTERN) LocalDateTime expiresAt,
    @JsonFormat(pattern = DatePattern.NORM_DATETIME_PATTERN) LocalDateTime lastUsedTime,
    Long totalCalls,
    AiLlmProviderEnum llmProvider,
    String maskedLlmApiKey,
    @JsonFormat(pattern = DatePattern.NORM_DATETIME_PATTERN) LocalDateTime createTime,
    @JsonFormat(pattern = DatePattern.NORM_DATETIME_PATTERN) LocalDateTime updateTime) {}
