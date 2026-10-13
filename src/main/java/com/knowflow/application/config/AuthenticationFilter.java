package com.knowflow.application.config;

import com.knowflow.application.common.SecurityHolder;
import com.knowflow.application.common.security.LoginToken;
import com.knowflow.application.common.security.RedisTokenManager;
import com.knowflow.application.enums.AdminRoleEnum;
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
 * 认证过滤器：
 *
 * <ul>
 *   <li>Token 有效：写入安全上下文，放行，并由 {@link RedisTokenManager#verifyToken} 自动滑动续期；
 *   <li>Token 无效/过期：不写上下文，放行后由 Security 决定 401。
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthenticationFilter extends OncePerRequestFilter {

  private final RedisTokenManager redisTokenManager;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
      throws ServletException, IOException {

    String authHeader = request.getHeader("Authorization");
    if (authHeader != null && !authHeader.isBlank()) {
      String token =
          authHeader.startsWith("Bearer ") ? authHeader.substring(7).trim() : authHeader.trim();
      authenticate(token);
    }

    // 无论是否认证成功都继续放行，由 Security 统一决定 401 / 403
    chain.doFilter(request, response);
  }

  private void authenticate(String token) {
    try {
      LoginToken loginToken = redisTokenManager.verifyToken(token);

      Long userId = loginToken.userId();
      if (userId == null) {
        log.debug("缺少有效的 userId，按未登录处理");
        return;
      }

      List<String> roles =
          loginToken.roles() != null
              ? loginToken.roles().stream().map(AdminRoleEnum::name).toList()
              : List.of();
      if (roles.isEmpty()) {
        SecurityHolder.setAuthentication(userId);
      } else {
        SecurityHolder.setAuthentication(userId, roles);
      }
    } catch (Exception e) {
      // 显式清空：防止因过滤器被容器/安全链重复执行而残留上一个请求的认证
      SecurityContextHolder.clearContext();
      log.debug("Token 校验失败，按未登录处理: {}", e.getMessage());
    }
  }
}
