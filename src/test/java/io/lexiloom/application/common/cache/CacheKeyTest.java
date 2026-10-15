package io.lexiloom.application.common.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CacheKeyTest {

  @Test
  @DisplayName("测试 CacheKey 工厂与格式化")
  void testCacheKey() {
    CacheKey<Long, String> key1 = CacheKey.of("c1", "p_%d", String.class);
    assertThat(key1.cacheName()).isEqualTo("c1");
    assertThat(key1.formatKey(10L)).isEqualTo("p_10");
    assertThat(key1.ttl()).isEqualTo(Duration.ofMillis(10));

    CacheKey<String, Integer> key2 =
        CacheKey.of("c2", "k_%s", Integer.class, Duration.ofMinutes(5));
    assertThat(key2.ttl()).isEqualTo(Duration.ofMinutes(5));
  }
}
