package io.lexiloom.application.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.lexiloom.application.common.BasePageRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

@DisplayName("OpenApiPrincipal 与 BasePageRequest 单元测试")
class OpenApiPrincipalTest {

  @Test
  @DisplayName("OpenApiPrincipal - 权限范围、空间白名单解析与密钥 toString 脱敏")
  void testOpenApiPrincipal() {
    OpenApiPrincipal principal =
        new OpenApiPrincipal(
            1L, 100L, "test-key", "kb:search, kb:chat", "10, 20, invalid", "OPENAI", "sk-secret");

    assertThat(principal.hasScope("kb:search")).isTrue();
    assertThat(principal.hasScope("kb:admin")).isFalse();
    assertThat(principal.isWorkspaceAllowed(10L)).isTrue();
    assertThat(principal.isWorkspaceAllowed(99L)).isFalse();
    assertThat(principal.isWorkspaceAllowed(null)).isFalse();
    assertThat(principal.toString()).doesNotContain("sk-secret").contains("[PROTECTED]");

    OpenApiPrincipal unrestricted =
        new OpenApiPrincipal(2L, 100L, "all-space", "", null, "QWEN", null);
    assertThat(unrestricted.isWorkspaceAllowed(999L)).isTrue();
    assertThat(unrestricted.hasScope("kb:search")).isFalse();
  }

  @Test
  @DisplayName("BasePageRequest - 页码下界与每页大小 Math.clamp 边界控制")
  void testBasePageRequest() {
    BasePageRequest req = new BasePageRequest();
    req.setPageNum(-5);
    req.setPageSize(500);

    Pageable pageable = req.toPageable();
    assertThat(pageable.getPageNumber()).isZero();
    assertThat(pageable.getPageSize()).isEqualTo(100);

    req.setPageNum(null);
    req.setPageSize(0);
    Pageable defaultPageable = req.toPageable(null);
    assertThat(defaultPageable.getPageNumber()).isZero();
    assertThat(defaultPageable.getPageSize()).isEqualTo(10);
  }
}
