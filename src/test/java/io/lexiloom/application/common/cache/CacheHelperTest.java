package io.lexiloom.application.common.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

@ExtendWith(MockitoExtension.class)
class CacheHelperTest {

  @Mock private CacheManager cacheManager;
  @Mock private Cache cache;

  @InjectMocks private CacheHelper cacheHelper;

  private final CacheKey<String, String> keySpec =
      CacheKey.of("testCache", "prefix_%s", String.class);

  @Test
  @DisplayName("getOrLoad - 成功获取或加载")
  void testGetOrLoad() {
    when(cacheManager.getCache("testCache")).thenReturn(cache);
    when(cache.get(eq("prefix_123"), any(Callable.class))).thenReturn("val123");

    String val = cacheHelper.getOrLoad(keySpec, "123", () -> "val123");
    assertThat(val).isEqualTo("val123");
  }

  @Test
  @DisplayName("get - 缓存未找到空间或存在值")
  void testGet() {
    when(cacheManager.getCache("testCache")).thenReturn(null);
    assertThat(cacheHelper.get(keySpec, "123")).isEmpty();

    when(cacheManager.getCache("testCache")).thenReturn(cache);
    when(cache.get("prefix_123", String.class)).thenReturn("val123");
    assertThat(cacheHelper.get(keySpec, "123")).contains("val123");
  }

  @Test
  @DisplayName("put - 写入缓存")
  void testPut() {
    when(cacheManager.getCache("testCache")).thenReturn(cache);
    cacheHelper.put(keySpec, "123", "val123");
    verify(cache).put("prefix_123", "val123");
  }

  @Test
  @DisplayName("evict - 清除缓存")
  void testEvict() {
    when(cacheManager.getCache("testCache")).thenReturn(null);
    cacheHelper.evict(keySpec, "123");

    when(cacheManager.getCache("testCache")).thenReturn(cache);
    cacheHelper.evict(keySpec, "123");
    verify(cache).evict("prefix_123");
  }

  @Test
  @DisplayName("getAndEvict - 命中并清除")
  void testGetAndEvict() {
    when(cacheManager.getCache("testCache")).thenReturn(null);
    assertThat(cacheHelper.getAndEvict(keySpec, "123")).isEmpty();

    when(cacheManager.getCache("testCache")).thenReturn(cache);
    when(cache.get("prefix_123", String.class)).thenReturn(null);
    assertThat(cacheHelper.getAndEvict(keySpec, "123")).isEmpty();

    when(cache.get("prefix_123", String.class)).thenReturn("val123");
    assertThat(cacheHelper.getAndEvict(keySpec, "123")).contains("val123");
    verify(cache).evict("prefix_123");
  }

  @Test
  @DisplayName("getRequiredCache - 缓存空间不存在抛出异常")
  void testGetRequiredCacheNotFound() {
    when(cacheManager.getCache("testCache")).thenReturn(null);
    assertThatThrownBy(() -> cacheHelper.put(keySpec, "123", "val"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("未配置对应的 Cache 空间");
  }
}
