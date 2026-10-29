package io.lexiloom.application.common.cache;

import java.time.Duration;
import java.util.Objects;

/**
 * 强类型缓存键定义 (不可变 record)
 *
 * @param <K> 业务主键类型
 * @param <V> 缓存值类型
 */
public record CacheKey<K, V>(
    String cacheName, String keyPattern, Class<V> valueType, Duration ttl) {

  public static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

  public CacheKey {
    Objects.requireNonNull(cacheName, "cacheName 不能为空");
    Objects.requireNonNull(keyPattern, "keyPattern 不能为空");
    Objects.requireNonNull(valueType, "valueType 不能为空");
    ttl = (ttl == null || ttl.isNegative() || ttl.isZero()) ? DEFAULT_TTL : ttl;
  }

  public static <K, V> CacheKey<K, V> of(String cacheName, String keyPattern, Class<V> valueType) {
    return new CacheKey<>(cacheName, keyPattern, valueType, DEFAULT_TTL);
  }

  public static <K, V> CacheKey<K, V> of(
      String cacheName, String keyPattern, Class<V> valueType, Duration ttl) {
    return new CacheKey<>(cacheName, keyPattern, valueType, ttl);
  }

  public String formatKey(K id) {
    return String.format(keyPattern, id);
  }
}
