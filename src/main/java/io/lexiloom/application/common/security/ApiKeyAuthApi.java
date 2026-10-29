package io.lexiloom.application.common.security;

import java.util.Optional;

/**
 * API Key 统一鉴权 SPI。
 *
 * <p>由具体业务模块（如 user）实现，认证过滤器仅依赖该抽象，避免基础设施层反向依赖业务模块。
 */
public interface ApiKeyAuthApi {

  /**
   * 验证 API Key 的合法性并返回对应的登录凭证 (包含用户 ID 和角色权限)
   *
   * @param apiKey API 密钥
   * @return 若密钥有效返回对应的 LoginToken，否则返回 empty
   */
  Optional<LoginToken> verifyApiKey(String apiKey);

  /**
   * 查询 OpenAPI Key 凭证上下文 (包含 Scopes、工作空间白名单及 BYOK 配置)
   *
   * @param apiKey API 密钥
   * @return 若匹配到有效 OpenAPI Key 则返回对应主体，否则返回 empty
   */
  Optional<OpenApiPrincipal> findOpenApiPrincipal(String apiKey);
}
