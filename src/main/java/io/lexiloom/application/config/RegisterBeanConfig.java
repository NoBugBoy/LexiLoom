package io.lexiloom.application.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.core.env.Environment;

public class RegisterBeanConfig implements BeanRegistrar {
  @Override
  public void register(BeanRegistry registry, Environment env) {
    var caffeineCacheManager = new CaffeineCacheManager();
    // default
    caffeineCacheManager.setCaffeine(
        Caffeine.newBuilder().expireAfterWrite(10, TimeUnit.MINUTES).maximumSize(1000));
    // ott
    caffeineCacheManager.registerCustomCache(
        "ottCode",
        Caffeine.newBuilder().expireAfterWrite(10, TimeUnit.MINUTES).maximumSize(1000).build());
    // tokenRenew (并发续期防抖缓存: 2分钟)
    caffeineCacheManager.registerCustomCache(
        "tokenRenew",
        Caffeine.newBuilder().expireAfterWrite(2, TimeUnit.MINUTES).maximumSize(5000).build());
    // tokenBlacklist (退出登录 jti 黑名单: 覆盖最长 1 小时的 token 有效期)
    caffeineCacheManager.registerCustomCache(
        "tokenBlacklist",
        Caffeine.newBuilder().expireAfterWrite(2, TimeUnit.HOURS).maximumSize(10000).build());
    registry.registerBean(
        CaffeineCacheManager.class,
        (caffeineCacheManagerSpec ->
            caffeineCacheManagerSpec.primary().supplier(context -> caffeineCacheManager)));
  }
}
