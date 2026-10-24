package io.lexiloom.application.user.api;

import io.lexiloom.application.common.security.LoginToken;
import io.lexiloom.application.user.api.dto.OpenApiKeyDTO;
import java.util.Optional;

/** API Key 统一鉴权验证对外契约 */
public interface ApiKeyAuthApi {

  /**
   * 验证 API Key 的合法性并返回对应的登录凭证 (包含 adminId 和角色权限)
   *
   * @param apiKey API 密钥
   * @return 若密钥有效返回对应的 LoginToken，否则返回 empty
   */
  Optional<LoginToken> verifyApiKey(String apiKey);

  /**
   * 查询 OpenAPI Key 凭证上下文 (包含 Scopes、工作空间白名单及 BYOK 配置)
   *
   * @param apiKey API 密钥
   * @return 若匹配到有效 OpenAPI Key 则返回对应 DTO，否则返回 empty
   */
  Optional<OpenApiKeyDTO> findOpenApiKey(String apiKey);
}
