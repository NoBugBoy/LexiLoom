package io.lexiloom.application.config;

import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.common.security.LoginToken;
import io.lexiloom.application.common.security.OpenApiPrincipal;
import io.lexiloom.application.common.security.RedisTokenManager;
import io.lexiloom.application.enums.AdminRoleEnum;
import io.lexiloom.application.user.api.ApiKeyAuthApi;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 统一身份认证过滤器 (支持 JWT Token 与 OpenAPI Key / Admin API Key 凭证校验)
 *
 * <p>兼容 HTTP 标头：
 *
 * <ul>
 *   <li>Authorization: Bearer lx_live_xxx 或常规 JWT
 *   <li>X-API-Key: lx_live_xxx
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthenticationFilter extends OncePerRequestFilter {

  private final RedisTokenManager redisTokenManager;
  private final ApiKeyAuthApi apiKeyAuthApi;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
      throws ServletException, IOException {

    AuthToken token = resolveToken(request);
    if (token != null) {
      authenticate(token);
    }

    // 无论是否认证成功都继续放行，由 Security 统一决定 401 / 403
    chain.doFilter(request, response);
  }

  private AuthToken resolveToken(HttpServletRequest request) {
    String authHeader = request.getHeader("Authorization");
    if (authHeader != null && !authHeader.isBlank()) {
      String raw =
          authHeader.startsWith("Bearer ") ? authHeader.substring(7).trim() : authHeader.trim();
      return new AuthToken(raw, raw.startsWith("lx_"));
    }
    String apiKeyHeader = request.getHeader("X-API-Key");
    if (apiKeyHeader != null && !apiKeyHeader.isBlank()) {
      return new AuthToken(apiKeyHeader.trim(), true);
    }
    return null;
  }

  private void authenticate(AuthToken token) {
    try {
      LoginToken loginToken;
      OpenApiPrincipal openApiPrincipal = null;

      if (token.isApiKey()) {
        loginToken = apiKeyAuthApi.verifyApiKey(token.value()).orElse(null);
        if (loginToken == null) {
          SecurityContextHolder.clearContext();
          log.debug("API Key 校验失败或已失效，按未登录处理");
          return;
        }

        openApiPrincipal =
            apiKeyAuthApi
                .findOpenApiKey(token.value())
                .map(
                    dto ->
                        new OpenApiPrincipal(
                            dto.id(),
                            dto.userId(),
                            dto.name(),
                            dto.scopes(),
                            dto.allowedWorkspaceIds(),
                            dto.llmProvider() != null ? dto.llmProvider().name() : "NONE",
                            dto.llmApiKey()))
                .orElse(null);
      } else {
        loginToken = redisTokenManager.verifyToken(token.value());
      }

      Long userId = loginToken.userId();
      if (userId == null) {
        log.debug("缺少有效的 userId，按未登录处理");
        return;
      }

      List<String> roles =
          loginToken.roles() != null
              ? loginToken.roles().stream().map(AdminRoleEnum::name).toList()
              : List.of();
      String apiKey = token.isApiKey() ? token.value() : null;
      SecurityHolder.setAuthentication(userId, roles, apiKey, openApiPrincipal);
    } catch (Exception e) {
      // 显式清空：防止因过滤器被容器/安全链重复执行而残留上一个请求的认证
      SecurityContextHolder.clearContext();
      log.debug("Token 校验失败，按未登录处理: {}", e.getMessage());
    }
  }

  private record AuthToken(String value, boolean isApiKey) {}
}
