package io.lexiloom.application.common;

import io.lexiloom.application.common.security.OpenApiPrincipal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 全局安全上下文访问持有类
 *
 * @author yujian
 */
public class SecurityHolder {
  private SecurityHolder() {}

  public static Long getUserId() {
    return getLoginUser().orElseThrow().userId();
  }

  public static Optional<String> getUsername() {
    return getLoginUser().map(LoginUserAuthentication::getName);
  }

  public static void setAuthentication(Long userId) {
    var user = new LoginUserAuthentication(userId, Collections.emptyList());
    SecurityContextHolder.getContext().setAuthentication(user);
  }

  public static void setAuthentication(Long userId, List<String> roles) {
    setAuthentication(userId, roles, null);
  }

  public static void setAuthentication(Long userId, List<String> roles, String apiKey) {
    setAuthentication(userId, roles, apiKey, null);
  }

  public static void setAuthentication(
      Long userId, List<String> roles, String apiKey, OpenApiPrincipal openApiPrincipal) {
    var user = new LoginUserAuthentication(userId, roles, apiKey, openApiPrincipal);
    SecurityContextHolder.getContext().setAuthentication(user);
  }

  public static Optional<String> getApiKey() {
    return getLoginUser().map(LoginUserAuthentication::apiKey);
  }

  public static Optional<OpenApiPrincipal> getOpenApiPrincipal() {
    return getLoginUser().map(LoginUserAuthentication::openApiPrincipal);
  }

  public static Optional<LoginUserAuthentication> getLoginUser() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof LoginUserAuthentication user
        && authentication.isAuthenticated()) {
      return Optional.of(user);
    }
    return Optional.empty();
  }
}
