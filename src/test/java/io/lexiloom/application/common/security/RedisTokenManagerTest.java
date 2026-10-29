package io.lexiloom.application.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.lexiloom.application.enums.AdminRoleEnum;
import io.lexiloom.application.exception.BusinessException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("RedisTokenManager 单元测试")
class RedisTokenManagerTest {

  @Mock private RedisTemplate<String, LoginToken> redisTemplate;
  @Mock private ValueOperations<String, LoginToken> valueOperations;

  @InjectMocks private RedisTokenManager redisTokenManager;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(redisTokenManager, "tokenExpireSeconds", 3600L);
    lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
  }

  @Test
  @DisplayName("generateToken - 成功生成并写入 Redis")
  void testGenerateToken() {
    String token = redisTokenManager.generateToken(101L, List.of(AdminRoleEnum.ADMIN));

    assertThat(token).isNotBlank();
    verify(valueOperations)
        .set(
            eq("user:login:token:" + token),
            eq(new LoginToken(101L, List.of(AdminRoleEnum.ADMIN))),
            eq(Duration.ofSeconds(3600L)));
  }

  @Test
  @DisplayName("verifyToken - 命中有效 Token 并滑动续期")
  void testVerifyTokenSuccess() {
    LoginToken stored = new LoginToken(101L, List.of(AdminRoleEnum.ADMIN));
    when(valueOperations.get("user:login:token:abc-123")).thenReturn(stored);

    LoginToken result = redisTokenManager.verifyToken("  abc-123  ");

    assertThat(result).isEqualTo(stored);
    verify(redisTemplate).expire("user:login:token:abc-123", Duration.ofSeconds(3600L));
  }

  @Test
  @DisplayName("verifyToken - 空白或不存在的 Token 抛出 401 异常")
  void testVerifyTokenInvalid() {
    assertThatThrownBy(() -> redisTokenManager.verifyToken("   "))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("token 失效");

    when(valueOperations.get("user:login:token:missing")).thenReturn(null);
    assertThatThrownBy(() -> redisTokenManager.verifyToken("missing"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("token 失效");
  }

  @Test
  @DisplayName("deleteToken - 空白忽略，非空正常删除")
  void testDeleteToken() {
    redisTokenManager.deleteToken("  ");
    verifyNoInteractions(redisTemplate);

    redisTokenManager.deleteToken(" token-xyz ");
    verify(redisTemplate).delete(any(String.class));
  }
}
