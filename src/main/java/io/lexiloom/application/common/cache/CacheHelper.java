package io.lexiloom.application.common.cache;

import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CacheHelper {
  private final CacheManager cacheManager;

  /** 1. 标准 Cache-Aside 模式（防击穿并发安全） 查缓存，存在则返回；不存在则调用 loader 加载并写回缓存 */
  public <K, V> V getOrLoad(CacheKey<K, V> keySpec, K id, Supplier<V> loader) {
    Cache cache = getRequiredCache(keySpec.cacheName());
    String key = keySpec.formatKey(id);

    // Spring Cache 的 get(key, Callable)内部自动带同步锁，防止多线程并发击穿
    return cache.get(
        key,
        () -> {
          log.debug("[Cache Miss] 缓存未命中，执行Loader: {}", key);
          return loader.get();
        });
  }

  /** 2. 获取缓存对象（返回 Optional，安全防 NPE） */
  public <K, V> Optional<V> get(CacheKey<K, V> keySpec, K id) {
    Cache cache = cacheManager.getCache(keySpec.cacheName());
    if (cache == null) return Optional.empty();

    String key = keySpec.formatKey(id);
    return Optional.ofNullable(cache.get(key, keySpec.valueType()));
  }

  /** 3. 写入缓存 */
  public <K, V> void put(CacheKey<K, V> keySpec, K id, V value) {
    Cache cache = getRequiredCache(keySpec.cacheName());
    cache.put(keySpec.formatKey(id), value);
  }

  /** 4. 清除缓存 */
  public <K, V> void evict(CacheKey<K, V> keySpec, K id) {
    Cache cache = cacheManager.getCache(keySpec.cacheName());
    if (cache != null) {
      cache.evict(keySpec.formatKey(id));
    }
  }

  /** 5. 原子消费：获取并立即清除（专用于 OTT、一次性验证码场景，防重放） */
  public <K, V> Optional<V> getAndEvict(CacheKey<K, V> keySpec, K id) {
    Cache cache = cacheManager.getCache(keySpec.cacheName());
    if (cache == null) return Optional.empty();

    String key = keySpec.formatKey(id);
    V value = cache.get(key, keySpec.valueType());
    if (value != null) {
      cache.evict(key); // 消费即焚
      return Optional.of(value);
    }
    return Optional.empty();
  }

  private Cache getRequiredCache(String cacheName) {
    Cache cache = cacheManager.getCache(cacheName);
    if (cache == null) {
      throw new IllegalStateException("未配置对应的 Cache 空间: " + cacheName);
    }
    return cache;
  }
}
