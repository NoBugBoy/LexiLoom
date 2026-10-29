package io.lexiloom.application.config;

import io.lexiloom.application.common.storage.FileStorageGateway;
import io.lexiloom.application.common.storage.RustfsStorageProperties;
import io.lexiloom.application.common.storage.adapter.LocalStorageAdapter;
import io.lexiloom.application.common.storage.adapter.RustfsStorageAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

/** 文件存储防腐层统一策略装配类 */
@Configuration
@Slf4j
@RequiredArgsConstructor
@EnableConfigurationProperties(RustfsStorageProperties.class)
public class FileStorageConfig {

  private final RustfsStorageProperties rustfsProperties;

  @Bean
  @Primary
  public FileStorageGateway fileStorageGateway() {
    if (rustfsProperties.isEnabled()) {
      if (!StringUtils.hasText(rustfsProperties.getBucketName())) {
        throw new IllegalStateException("启用 rustfs 存储时，lexiloom.storage.rustfs.bucket-name 不能为空");
      }
      log.info(
          "启用 rustfs 对象存储适配器: endpoint={}, bucket={}",
          rustfsProperties.getEndpoint(),
          rustfsProperties.getBucketName());
      return new RustfsStorageAdapter(rustfsProperties);
    }

    log.info("未启用 rustfs 对象存储，使用本地文件存储适配器 (LocalStorageAdapter)");
    return new LocalStorageAdapter();
  }
}
