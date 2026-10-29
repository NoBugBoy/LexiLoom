package io.lexiloom.application.config;

import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.common.security.LoginToken;
import io.lexiloom.application.common.security.ApiKeyAuthApi;
import io.lexiloom.application.common.security.OpenApiPrincipal;
import io.lexiloom.application.common.security.RedisTokenManager;
import io.lexiloom.application.enums.AdminRoleEnum;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 统一身份认证过滤器 (支持 JWT Token 与 OpenAPI Key / Admin API Key 凭证校验)
 *
 * <p>兼容 HTTP 标头：
 *
 * <ul>
 *   <li>Authorization: Bearer lx_live_xxx 或常规会话 Token
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

    AuthToken.resolve(request).ifPresent(this::authenticate);

    // 无论是否认证成功都继续放行，由 Security 统一决定 401 / 403
    chain.doFilter(request, response);
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

        openApiPrincipal = apiKeyAuthApi.findOpenApiPrincipal(token.value()).orElse(null);
      } else {
        loginToken = redisTokenManager.verifyToken(token.value());
      }

      if (loginToken == null || loginToken.userId() == null) {
        SecurityContextHolder.clearContext();
        log.debug("缺少有效的 userId，按未登录处理");
        return;
      }

      List<String> roles =
          loginToken.roles() != null
              ? loginToken.roles().stream().map(AdminRoleEnum::name).toList()
              : List.of();
      String apiKey = token.isApiKey() ? token.value() : null;
      SecurityHolder.setAuthentication(loginToken.userId(), roles, apiKey, openApiPrincipal);
    } catch (Exception e) {
      // 显式清空：防止因过滤器被容器/安全链重复执行而残留上一个请求的认证
      SecurityContextHolder.clearContext();
      log.debug("Token 校验失败，按未登录处理: {}", e.getMessage());
    }
  }

  private record AuthToken(String value, boolean isApiKey) {
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String API_KEY_PREFIX = "lx_";
    private static final String HEADER_X_API_KEY = "X-API-Key";

    static Optional<AuthToken> resolve(HttpServletRequest request) {
      return extractTokenValue(request.getHeader(HttpHeaders.AUTHORIZATION))
          .map(raw -> new AuthToken(raw, raw.startsWith(API_KEY_PREFIX)))
          .or(
              () ->
                  Optional.ofNullable(request.getHeader(HEADER_X_API_KEY))
                      .map(String::trim)
                      .filter(AuthToken::isValidTokenFormat)
                      .map(raw -> new AuthToken(raw, true)));
    }

    private static Optional<String> extractTokenValue(String authHeader) {
      if (!StringUtils.hasText(authHeader)) {
        return Optional.empty();
      }
      String trimmed = authHeader.trim();
      if ("Bearer".equalsIgnoreCase(trimmed)) {
        return Optional.empty();
      }
      String candidate =
          trimmed.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())
              ? trimmed.substring(BEARER_PREFIX.length()).trim()
              : trimmed;
      return isValidTokenFormat(candidate) ? Optional.of(candidate) : Optional.empty();
    }

    private static boolean isValidTokenFormat(String token) {
      return StringUtils.hasText(token) && !StringUtils.containsWhitespace(token);
    }
  }
}
