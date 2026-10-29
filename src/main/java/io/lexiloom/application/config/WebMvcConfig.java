package io.lexiloom.application.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    String uploadAbsolutePath =
        Path.of("uploads").toAbsolutePath().normalize().toUri().toString();
    if (!uploadAbsolutePath.endsWith("/")) {
      uploadAbsolutePath += "/";
    }
    registry
        .addResourceHandler("/uploads/**")
        .addResourceLocations(uploadAbsolutePath)
        .setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic());
  }
}
