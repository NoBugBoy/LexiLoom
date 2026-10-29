package io.lexiloom.application.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.common.security.LoginToken;
import io.lexiloom.application.common.security.RedisTokenManager;
import io.lexiloom.application.enums.AdminRoleEnum;
import io.lexiloom.application.common.security.ApiKeyAuthApi;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class AuthenticationFilterTest {

  @Mock private RedisTokenManager redisTokenManager;
  @Mock private ApiKeyAuthApi apiKeyAuthApi;
  @Mock private FilterChain filterChain;

  @InjectMocks private AuthenticationFilter filter;

  @BeforeEach
  @AfterEach
  void cleanContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("请求头包含 Authorization: Bearer lx_... 形式 API Key 能够成功鉴权并写入安全上下文及角色")
  void testAuthenticateWithBearerApiKey() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer lx_live_test_api_key_123");
    MockHttpServletResponse response = new MockHttpServletResponse();

    LoginToken token = new LoginToken(88L, List.of(AdminRoleEnum.ADMIN));
    when(apiKeyAuthApi.verifyApiKey("lx_live_test_api_key_123")).thenReturn(Optional.of(token));

    filter.doFilter(request, response, filterChain);

    assertThat(SecurityHolder.getLoginUser()).isPresent();
    assertThat(SecurityHolder.getUserId()).isEqualTo(88L);
    assertThat(SecurityHolder.getLoginUser().get().roles()).containsExactly("ADMIN");
    assertThat(SecurityHolder.getApiKey()).contains("lx_live_test_api_key_123");
    verify(filterChain).doFilter(request, response);
    verifyNoInteractions(redisTokenManager);
  }

  @Test
  @DisplayName("请求头包含 X-API-Key 能够成功鉴权并写入安全上下文及角色")
  void testAuthenticateWithXApiKeyHeader() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-API-Key", "lx_live_x_api_key_456");
    MockHttpServletResponse response = new MockHttpServletResponse();

    LoginToken token = new LoginToken(99L, List.of(AdminRoleEnum.SUPER_ADMIN));
    when(apiKeyAuthApi.verifyApiKey("lx_live_x_api_key_456")).thenReturn(Optional.of(token));

    filter.doFilter(request, response, filterChain);

    assertThat(SecurityHolder.getLoginUser()).isPresent();
    assertThat(SecurityHolder.getUserId()).isEqualTo(99L);
    assertThat(SecurityHolder.getLoginUser().get().roles()).containsExactly("SUPER_ADMIN");
    assertThat(SecurityHolder.getApiKey()).contains("lx_live_x_api_key_456");
    verify(filterChain).doFilter(request, response);
    verifyNoInteractions(redisTokenManager);
  }

  @Test
  @DisplayName("普通登录用户的 Bearer Token 由 RedisTokenManager 鉴权")
  void testAuthenticateWithNormalLoginToken() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer session_token_uuid");
    MockHttpServletResponse response = new MockHttpServletResponse();

    LoginToken loginToken = new LoginToken(100L, Collections.emptyList());
    when(redisTokenManager.verifyToken("session_token_uuid")).thenReturn(loginToken);

    filter.doFilter(request, response, filterChain);

    assertThat(SecurityHolder.getLoginUser()).isPresent();
    assertThat(SecurityHolder.getUserId()).isEqualTo(100L);
    verify(filterChain).doFilter(request, response);
    verifyNoInteractions(apiKeyAuthApi);
  }

  @Test
  @DisplayName("无认证头请求直接放行，安全上下文保持未登录状态")
  void testNoAuthHeaderPassThrough() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    assertThat(SecurityHolder.getLoginUser()).isEmpty();
    verify(filterChain).doFilter(request, response);
    verifyNoInteractions(apiKeyAuthApi, redisTokenManager);
  }

  @Test
  @DisplayName("兼容小写 bearer 前缀与非 Bearer 方案（如 Basic）自动回退 X-API-Key")
  void testCaseInsensitiveBearerAndBlankFallback() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
    request.addHeader("X-API-Key", "lx_live_fallback_789");
    MockHttpServletResponse response = new MockHttpServletResponse();

    LoginToken token = new LoginToken(77L, List.of(AdminRoleEnum.ADMIN));
    when(apiKeyAuthApi.verifyApiKey("lx_live_fallback_789")).thenReturn(Optional.of(token));

    filter.doFilter(request, response, filterChain);

    assertThat(SecurityHolder.getLoginUser()).isPresent();
    assertThat(SecurityHolder.getUserId()).isEqualTo(77L);
    verify(filterChain).doFilter(request, response);
    verifyNoInteractions(redisTokenManager);
  }
}
