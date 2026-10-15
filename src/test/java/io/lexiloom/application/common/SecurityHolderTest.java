package io.lexiloom.application.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

class SecurityHolderTest {

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("设置并获取当前用户ID")
  void testSetAndGet() {
    SecurityHolder.setAuthentication(123L);
    assertThat(SecurityHolder.getUserId()).isEqualTo(123L);
    assertThat(SecurityHolder.getLoginUser()).isPresent();
    assertThat(SecurityHolder.getLoginUser().get().userId()).isEqualTo(123L);
  }

  @Test
  @DisplayName("设置并获取当前用户ID与角色")
  void testSetAndGetWithRoles() {
    SecurityHolder.setAuthentication(456L, List.of("ADMIN"));
    assertThat(SecurityHolder.getUserId()).isEqualTo(456L);
    assertThat(SecurityHolder.getLoginUser().get().roles()).containsExactly("ADMIN");
  }

  @Test
  @DisplayName("未登录获取用户抛出异常")
  void testNotLoggedIn() {
    assertThat(SecurityHolder.getLoginUser()).isEmpty();
    assertThatThrownBy(SecurityHolder::getUserId).isInstanceOf(NoSuchElementException.class);
  }
}
