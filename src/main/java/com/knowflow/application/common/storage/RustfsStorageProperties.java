package com.knowflow.application.common.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** rustfs 对象存储配置属性 rustfs 是基于 Rust 编写的高性能、S3 兼容的分布式对象存储引擎 */
@Data
@ConfigurationProperties(prefix = "knowflow.storage.rustfs")
public class RustfsStorageProperties {

  /** 是否启用 rustfs 作为对象存储底座 */
  private boolean enabled = false;

  /** rustfs 服务端点 (S3 兼容 API 端点，例如 http://localhost:9000) */
  private String endpoint = "http://localhost:9000";

  /** 存储桶名称 */
  private String bucketName = "knowflow";

  /** Access Key */
  private String accessKey = "rustfsadmin";

  /** Secret Key */
  private String secretKey = "rustfsadmin";

  /** 区域 */
  private String region = "us-east-1";
}
