package io.lexiloom.application.common;

import io.lexiloom.application.common.security.OpenApiPrincipal;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * 全局安全上下文登录用户凭证模型
 *
 * @author yujian
 */
public record LoginUserAuthentication(
    Long userId,
    List<String> roles,
    @Nullable String apiKey,
    @Nullable OpenApiPrincipal openApiPrincipal)
    implements Authentication {

  public LoginUserAuthentication {
    roles = roles == null ? List.of() : List.copyOf(roles);
  }

  public LoginUserAuthentication(Long userId, List<String> roles, @Nullable String apiKey) {
    this(userId, roles, apiKey, null);
  }

  public LoginUserAuthentication(Long userId, List<String> roles) {
    this(userId, roles, null, null);
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    // hasAnyRole/hasRole 期望 ROLE_ 前缀，而 token 里存的是不带前缀的角色名
    return roles.stream()
        .map(role -> new SimpleGrantedAuthority(role.startsWith("ROLE_") ? role : "ROLE_" + role))
        .toList();
  }

  @Override
  public @Nullable Object getCredentials() {
    return null;
  }

  @Override
  public @Nullable Object getDetails() {
    return this;
  }

  @Override
  public @Nullable Object getPrincipal() {
    return this;
  }

  @Override
  public boolean isAuthenticated() {
    return true;
  }

  @Override
  public void setAuthenticated(boolean isAuthenticated) throws IllegalArgumentException {
    // 该认证对象始终已认证，不允许外部修改认证状态。
  }

  @Override
  public String getName() {
    return String.valueOf(this.userId);
  }
}
