package io.lexiloom.application.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.core.env.Environment;

public class RegisterBeanConfig implements BeanRegistrar {

  @Override
  public void register(BeanRegistry registry, Environment env) {
    registry.registerBean(
        CaffeineCacheManager.class,
        spec -> spec.primary().supplier(context -> buildCaffeineCacheManager()));
  }

  private static CaffeineCacheManager buildCaffeineCacheManager() {
    var manager = new CaffeineCacheManager();
    // 默认缓存策略: 写入后 10 分钟过期，最大容量 1000
    manager.setCaffeine(
        Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(10)).maximumSize(1000));

    // 管理员注册与密码重置一次性令牌 (对齐 AccountCacheKey)
    registerCustomCache(manager, "register_ott", Duration.ofMinutes(10), 1000);
    registerCustomCache(manager, "password_reset", Duration.ofMinutes(10), 1000);

    // 通用 OTT 验证码缓存
    registerCustomCache(manager, "ottCode", Duration.ofMinutes(10), 1000);

    // tokenRenew (并发续期防抖缓存: 2分钟)
    registerCustomCache(manager, "tokenRenew", Duration.ofMinutes(2), 5000);

    // tokenBlacklist (退出登录 jti 黑名单: 覆盖最长 2 小时的 token 有效期)
    registerCustomCache(manager, "tokenBlacklist", Duration.ofHours(2), 10000);

    return manager;
  }

  private static void registerCustomCache(
      CaffeineCacheManager manager, String cacheName, Duration ttl, long maxSize) {
    manager.registerCustomCache(
        cacheName, Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(maxSize).build());
  }
}
