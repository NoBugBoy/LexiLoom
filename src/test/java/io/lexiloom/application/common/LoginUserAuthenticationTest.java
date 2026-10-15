package io.lexiloom.application.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

class LoginUserAuthenticationTest {

  @Test
  @DisplayName("测试 LoginUserAuthentication 全字段和接口方法")
  void testAllMethods() {
    LoginUserAuthentication auth = new LoginUserAuthentication(123L, List.of("ROLE_ADMIN", "USER"));

    assertThat(auth.userId()).isEqualTo(123L);
    assertThat(auth.roles()).containsExactly("ROLE_ADMIN", "USER");
    assertThat(auth.getName()).isEqualTo("123");
    assertThat(auth.isAuthenticated()).isTrue();
    assertThat(auth.getCredentials()).isNull();
    assertThat(auth.getDetails()).isEqualTo(auth);
    assertThat(auth.getPrincipal()).isEqualTo(auth);

    // setAuthenticated 为空实现
    auth.setAuthenticated(false);
    assertThat(auth.isAuthenticated()).isTrue();

    // 验证 authorities 包含 ROLE_ 前缀
    Collection<? extends GrantedAuthority> authorities = auth.getAuthorities();
    assertThat(authorities)
        .extracting(GrantedAuthority::getAuthority)
        .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_USER");
  }
}
