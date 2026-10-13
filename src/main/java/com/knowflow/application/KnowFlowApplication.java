package com.knowflow.application;

import com.knowflow.application.config.RegisterBeanConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
@ConfigurationPropertiesScan
@EnableAsync
@EnableSpringDataWebSupport(
    pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
@EntityScan(basePackages = {"com.knowflow.application", "org.springframework.modulith.events.jpa"})
@Import(RegisterBeanConfig.class)
public class KnowFlowApplication {
  /**
   * 入口
   *
   * @param args
   */
  public static void main(String[] args) {
    SpringApplication.run(KnowFlowApplication.class, args);
  }
}
