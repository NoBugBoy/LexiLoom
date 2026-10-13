package com.knowflow.application.common.security;

import com.knowflow.application.enums.AdminRoleEnum;
import com.knowflow.application.exception.BusinessException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
    String token = UUID.randomUUID().toString();
    redisTemplate
        .opsForValue()
        .set(
            TOKEN_KEY + token,
            new LoginToken(userId, roles),
            Duration.ofSeconds(tokenExpireSeconds));
    return token;
  }

  public LoginToken verifyToken(String token) {
    String key = TOKEN_KEY + token;
    LoginToken loginToken = redisTemplate.opsForValue().get(key);
    if (loginToken == null) {
      throw BusinessException.unauthorized("token 失效");
    }
    // 滑动续期：每次验证成功后重置 TTL，保持活跃用户不掉线
    redisTemplate.expire(key, Duration.ofSeconds(tokenExpireSeconds));
    return loginToken;
  }

  public void deleteToken(String token) {
    redisTemplate.delete(TOKEN_KEY + token);
  }
}
