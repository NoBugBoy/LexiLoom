package io.lexiloom.application.config;

import java.nio.file.Paths;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    String uploadAbsolutePath =
        Paths.get("uploads").toAbsolutePath().normalize().toUri().toString();
    if (!uploadAbsolutePath.endsWith("/")) {
      uploadAbsolutePath += "/";
    }
    registry.addResourceHandler("/uploads/**").addResourceLocations(uploadAbsolutePath);
  }
}
