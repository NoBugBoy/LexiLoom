package io.lexiloom.application.config;

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
    RedisTemplate<String, LoginToken> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);
    template.setKeySerializer(new StringRedisSerializer());
    JacksonJsonRedisSerializer<LoginToken> serializer =
        new JacksonJsonRedisSerializer<>(objectMapper, LoginToken.class);
    template.setValueSerializer(serializer);
    template.setHashKeySerializer(new StringRedisSerializer());
    template.setHashValueSerializer(serializer);
    template.afterPropertiesSet();
    return template;
  }

  /** 注册兼容旧版 Jackson 2 的 ObjectMapper Bean，供依赖 com.fasterxml.jackson 的组件与第三方库透明使用 */
  @Bean
  @ConditionalOnMissingBean(com.fasterxml.jackson.databind.ObjectMapper.class)
  public com.fasterxml.jackson.databind.ObjectMapper jackson2ObjectMapper() {
    com.fasterxml.jackson.databind.ObjectMapper mapper =
        new com.fasterxml.jackson.databind.ObjectMapper();
    mapper.findAndRegisterModules();
    return mapper;
  }
}
