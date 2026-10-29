package io.lexiloom.application.common;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.common.security.RedisTokenManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 会话登出服务：从 Redis 中移除当前登录会话 Token，使其立即失效。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionLogoutService {

  private static final String BEARER_PREFIX = "Bearer ";

  private final RedisTokenManager redisTokenManager;

  /**
   * 登出当前 Token。幂等：token 缺失、空白或已过期均视为已登出，不抛异常。
   *
   * @param token 请求的 Token 或 Authorization 头（可能为 null 或带 Bearer 前缀）
   */
  public void logout(String token) {
    if (StrUtil.isBlank(token)) {
      log.debug("退出登录：无有效 Token，幂等跳过");
      return;
    }

    String cleanToken = token.trim();
    if (cleanToken.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
      cleanToken = cleanToken.substring(BEARER_PREFIX.length()).trim();
    } else if (cleanToken.equalsIgnoreCase("Bearer")) {
      return;
    }
    if (cleanToken.isEmpty()) {
      return;
    }

    redisTokenManager.deleteToken(cleanToken);
  }
}
