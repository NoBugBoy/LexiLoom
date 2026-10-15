package io.lexiloom.application.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.lexiloom.application.common.security.LoginToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import tools.jackson.databind.ObjectMapper;

class RedisConfigTest {

  @Test
  @DisplayName("验证 RedisConfig 成功装配 Jackson 3 ObjectMapper 与 RedisTemplate")
  void testLoginTokenRedisTemplate() {
    RedisConfig config = new RedisConfig();
    RedisConnectionFactory mockFactory = mock(RedisConnectionFactory.class);
    ObjectMapper jackson3Mapper = new ObjectMapper();

    RedisTemplate<String, LoginToken> template =
        config.loginTokenRedisTemplate(mockFactory, jackson3Mapper);

    assertThat(template).isNotNull();
    assertThat(template.getConnectionFactory()).isEqualTo(mockFactory);
    assertThat(template.getKeySerializer()).isNotNull();
    assertThat(template.getValueSerializer()).isNotNull();
  }

  @Test
  @DisplayName("验证 RedisConfig 注册的兼容版 Jackson 2 ObjectMapper")
  void testJackson2ObjectMapper() {
    RedisConfig config = new RedisConfig();
    com.fasterxml.jackson.databind.ObjectMapper mapper = config.jackson2ObjectMapper();

    assertThat(mapper).isNotNull();
  }
}
