package io.lexiloom.application.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QuerydslConfig {
  @Bean
  public JPAQueryFactory japQueryFactory(EntityManager entityManager) {
    return new JPAQueryFactory(entityManager);
  }
}
