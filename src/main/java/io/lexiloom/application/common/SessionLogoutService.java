package io.lexiloom.application.common;

import io.lexiloom.application.common.security.RedisTokenManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 退出登录：把当前 Bearer Token 的 jti 写入黑名单，之后携带该 Token 的请求会被 JWT 过滤器拒绝。
 *
 * <p>无状态 JWT 本身无法“服务端删除”，只能靠黑名单让 token 提前失效； 黑名单记录由 Caffeine 在 2 小时后自动清理，覆盖 1 小时的 token 有效期。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionLogoutService {

  private final RedisTokenManager redisTokenManager;

  /**
   * 登出当前 Token。幂等：token 缺失、已过期或签名无效都视为已登出，不抛异常。
   *
   * @param token 请求的 Authorization 头（可能为 null）
   */
  public void logout(String token) {
    if (token == null) {
      log.debug("退出登录：无有效 Token，处理");
      return;
    }

    redisTokenManager.deleteToken(token);
  }
}
