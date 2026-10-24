package io.lexiloom.application.user.model.response;

import io.lexiloom.application.user.model.vo.OpenApiKeyVO;

/**
 * 成功创建 OpenAPI 凭据响应体 (包含仅返回一次的完整原始明文 Key)
 */
public record CreateOpenApiKeyResponse(
    OpenApiKeyVO keyInfo,
    String rawApiKey) {}
