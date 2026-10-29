package io.lexiloom.application.common.security;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.enums.AdminRoleEnum;
import io.lexiloom.application.exception.BusinessException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedisTokenManager {

  private static final String TOKEN_KEY = "user:login:token:";

  private final RedisTemplate<String, LoginToken> redisTemplate;

  @Value("${security.token.expire-seconds:3600}")
  private long tokenExpireSeconds;

  public String generateToken(Long userId, List<AdminRoleEnum> roles) {
    Objects.requireNonNull(userId, "userId 不能为空");
    String token = UUID.randomUUID().toString();
    List<AdminRoleEnum> safeRoles = roles == null ? List.of() : List.copyOf(roles);
    redisTemplate
        .opsForValue()
        .set(
            buildKey(token),
            new LoginToken(userId, safeRoles),
            Duration.ofSeconds(tokenExpireSeconds));
    return token;
  }

  private static @NonNull String buildKey(String token) {
    return TOKEN_KEY + token;
  }

  public LoginToken verifyToken(String token) {
    if (StrUtil.isBlank(token)) {
      throw BusinessException.unauthorized("token 失效");
    }
    String key = buildKey(token.trim());
    LoginToken loginToken = redisTemplate.opsForValue().get(key);
    if (loginToken == null) {
      throw BusinessException.unauthorized("token 失效");
    }
    // 滑动续期：每次验证成功后重置 TTL，保持活跃用户不掉线
    redisTemplate.expire(key, Duration.ofSeconds(tokenExpireSeconds));
    return loginToken;
  }

  public void deleteToken(String token) {
    if (StrUtil.isBlank(token)) {
      return;
    }
    redisTemplate.delete(buildKey(token.trim()));
  }
}
