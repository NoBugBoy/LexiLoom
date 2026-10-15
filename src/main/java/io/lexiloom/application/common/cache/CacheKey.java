package io.lexiloom.application.common.cache;

import java.time.Duration;

public record CacheKey<K, V>(
    String cacheName, String keyPattern, Class<V> valueType, Duration ttl) {

  public static <K, V> CacheKey<K, V> of(String cacheName, String keyPattern, Class<V> valueType) {
    return new CacheKey<>(cacheName, keyPattern, valueType, Duration.ofMillis(10));
  }

  public static <K, V> CacheKey<K, V> of(
      String cacheName, String keyPattern, Class<V> valueType, Duration ttl) {
    return new CacheKey<>(cacheName, keyPattern, valueType, ttl);
  }

  public String formatKey(K id) {
    return String.format(keyPattern, id);
  }
}
