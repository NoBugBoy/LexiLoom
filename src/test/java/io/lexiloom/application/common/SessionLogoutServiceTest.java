package io.lexiloom.application.common;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.lexiloom.application.common.security.RedisTokenManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionLogoutServiceTest {

  @Mock private RedisTokenManager redisTokenManager;

  @InjectMocks private SessionLogoutService sessionLogoutService;

  @Test
  @DisplayName("logout - 空 token 幂等，不调用 redis")
  void testLogoutNullHeader() {
    sessionLogoutService.logout(null);
    verifyNoInteractions(redisTokenManager);
  }

  @Test
  @DisplayName("logout - 正常登出删除 Redis Token")
  void testLogoutSuccess() {
    String token = "test-token-uuid";
    sessionLogoutService.logout(token);
    verify(redisTokenManager).deleteToken(token);
  }
}
