package io.lexiloom.application.common;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
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
    var user = new LoginUserAuthentication(userId, roles);
    SecurityContextHolder.getContext().setAuthentication(user);
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
