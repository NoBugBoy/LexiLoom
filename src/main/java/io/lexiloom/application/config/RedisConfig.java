package io.lexiloom.application.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.lexiloom.application.common.security.LoginToken;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class RedisConfig {

  @Bean
  public RedisTemplate<String, LoginToken> loginTokenRedisTemplate(
      RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
    return buildRedisTemplate(connectionFactory, objectMapper, LoginToken.class);
  }

  @Bean
  @ConditionalOnMissingBean(name = "redisTemplate")
  public RedisTemplate<String, Object> redisTemplate(
      RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
    return buildRedisTemplate(connectionFactory, objectMapper, Object.class);
  }

  /** 注册兼容旧版 Jackson 2 的 ObjectMapper Bean，供依赖 com.fasterxml.jackson 的组件与第三方库透明使用 */
  @Bean
  @ConditionalOnMissingBean(com.fasterxml.jackson.databind.ObjectMapper.class)
  public com.fasterxml.jackson.databind.ObjectMapper jackson2ObjectMapper() {
    com.fasterxml.jackson.databind.ObjectMapper mapper =
        new com.fasterxml.jackson.databind.ObjectMapper();
    mapper.findAndRegisterModules();
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    return mapper;
  }

  private <T> RedisTemplate<String, T> buildRedisTemplate(
      RedisConnectionFactory connectionFactory, ObjectMapper objectMapper, Class<T> valueType) {
    RedisTemplate<String, T> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);
    JacksonJsonRedisSerializer<T> valueSerializer =
        new JacksonJsonRedisSerializer<>(objectMapper, valueType);
    template.setKeySerializer(StringRedisSerializer.UTF_8);
    template.setValueSerializer(valueSerializer);
    template.setHashKeySerializer(StringRedisSerializer.UTF_8);
    template.setHashValueSerializer(valueSerializer);
    template.afterPropertiesSet();
    return template;
  }
}
