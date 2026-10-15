package io.lexiloom.application.common.storage.adapter;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.common.storage.FileStorageGateway;
import io.lexiloom.application.common.storage.RustfsStorageProperties;
import io.lexiloom.application.exception.BusinessException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.extern.slf4j.Slf4j;

/**
 * rustfs 对象存储适配器 对接基于 Rust 编写的 S3 兼容高性能分布式对象存储系统 (rustfs) 提供真实 S3/MinIO API 协议文件上传、下载、删除及预留本地目录双模容灾
 */
@Slf4j
public class RustfsStorageAdapter implements FileStorageGateway {

  private final RustfsStorageProperties properties;
  private final MinioClient minioClient;
  private final LocalStorageAdapter localFallback;
  private static final String RUSTFS_LOCAL_DATA = "rustfs-data";
  private volatile boolean bucketChecked = false;

  public RustfsStorageAdapter(RustfsStorageProperties properties) {
    this.properties = properties;
    this.localFallback = new LocalStorageAdapter();
    MinioClient client = null;
    try {
      if (StrUtil.isNotBlank(properties.getEndpoint())) {
        client =
            MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
        log.info(
            "初始化 rustfs 存储客户端 (S3 兼容): endpoint={}, bucket={}",
            properties.getEndpoint(),
            properties.getBucketName());
      }
    } catch (Exception e) {
      log.warn("初始化 rustfs S3 客户端失败，将使用本地文件模拟模式: {}", e.getMessage());
    }
    this.minioClient = client;
  }

  @Override
  public String upload(
      String objectKey, InputStream inputStream, String contentType, long contentLength) {
    String cleanKey = safeKey(objectKey);
    byte[] fileBytes;
    try {
      fileBytes = IoUtil.readBytes(inputStream);
    } catch (Exception e) {
      log.error("读取待上传文件流失败: key={}, error={}", cleanKey, e.getMessage(), e);
      throw BusinessException.badRequest("读取上传文件内容失败");
    }

    // 1. 若配置了 MinioClient，通过标准 S3 协议上传至远端 RustFS 存储桶
    if (minioClient != null) {
      try {
        ensureBucketExists();
        try (ByteArrayInputStream in = new ByteArrayInputStream(fileBytes)) {
          minioClient.putObject(
              PutObjectArgs.builder().bucket(properties.getBucketName()).object(cleanKey).stream(
                      in, fileBytes.length, -1)
                  .contentType(StrUtil.blankToDefault(contentType, "application/octet-stream"))
                  .build());
          log.info(
              "rustfs 对象存储上传成功 (S3 远端): bucket={}, objectKey={}, size={} bytes",
              properties.getBucketName(),
              cleanKey,
              fileBytes.length);
          return getUrl(cleanKey);
        }
      } catch (Exception e) {
        log.error(
            "rustfs S3 远端上传失败: bucket={}, key={}, error={}",
            properties.getBucketName(),
            cleanKey,
            e.getMessage(),
            e);
        throw BusinessException.badRequest("上传至 rustfs 对象存储失败: " + e.getMessage());
      }
    }

    // 2. 本地模拟存储（仅在未配置 S3 远端客户端的单测或无网环境使用）
    try {
      Path targetPath =
          StoragePathGuard.resolveWithin(
              Paths.get(RUSTFS_LOCAL_DATA, properties.getBucketName()), cleanKey);
      File file = targetPath.toFile();
      FileUtil.mkdir(file.getParentFile());
      FileUtil.writeBytes(fileBytes, file);
      log.info(
          "rustfs 存储上传成功 (本地模拟): bucket={}, objectKey={}, size={} bytes",
          properties.getBucketName(),
          cleanKey,
          fileBytes.length);
      return getUrl(cleanKey);
    } catch (Exception e) {
      log.error("rustfs 存储文件保存失败: objectKey={}, error={}", cleanKey, e.getMessage(), e);
      throw BusinessException.badRequest("rustfs 文件存储失败，请稍后重试");
    }
  }

  @Override
  public void delete(String objectKeyOrUrl) {
    String cleanKey = safeKey(objectKeyOrUrl);
    if (minioClient != null) {
      try {
        minioClient.removeObject(
            RemoveObjectArgs.builder().bucket(properties.getBucketName()).object(cleanKey).build());
        log.info("rustfs 删除远端文件成功: bucket={}, key={}", properties.getBucketName(), cleanKey);
        return;
      } catch (Exception e) {
        log.error("rustfs 删除远端文件失败: key={}, error={}", cleanKey, e.getMessage());
        throw BusinessException.badRequest("删除 rustfs 远端文件失败: " + e.getMessage());
      }
    }

    try {
      Path targetPath =
          StoragePathGuard.resolveWithin(
              Paths.get(RUSTFS_LOCAL_DATA, properties.getBucketName()), cleanKey);
      FileUtil.del(targetPath.toFile());
    } catch (Exception ignored) {
    }
  }

  @Override
  public String getUrl(String objectKey) {
    String cleanKey = cleanKey(objectKey);
    if (StrUtil.isNotBlank(properties.getEndpoint())) {
      String endpoint = properties.getEndpoint().replaceAll("/+$", "");
      return endpoint + "/" + properties.getBucketName() + "/" + cleanKey;
    }
    return "/rustfs/" + properties.getBucketName() + "/" + cleanKey;
  }

  @Override
  public InputStream download(String objectKeyOrUrl) {
    String cleanKey = safeKey(objectKeyOrUrl);
    if (minioClient != null) {
      try {
        return minioClient.getObject(
            GetObjectArgs.builder().bucket(properties.getBucketName()).object(cleanKey).build());
      } catch (Exception e) {
        log.error("从 rustfs S3 远端拉取失败: key={}, error={}", cleanKey, e.getMessage(), e);
        throw BusinessException.notFound("存储文件不存在或远端读取失败: " + cleanKey);
      }
    }

    try {
      Path targetPath =
          StoragePathGuard.resolveWithin(
              Paths.get(RUSTFS_LOCAL_DATA, properties.getBucketName()), cleanKey);
      File file = targetPath.toFile();
      if (file.exists()) {
        return FileUtil.getInputStream(file);
      }
      return localFallback.download(objectKeyOrUrl);
    } catch (BusinessException be) {
      throw be;
    } catch (Exception e) {
      log.error("rustfs 读取文件失败: key={}, error={}", objectKeyOrUrl, e.getMessage(), e);
      throw BusinessException.badRequest("读取存储文件失败");
    }
  }

  @Override
  public boolean exists(String objectKeyOrUrl) {
    String cleanKey = safeKey(objectKeyOrUrl);
    if (minioClient != null) {
      try {
        minioClient.statObject(
            StatObjectArgs.builder().bucket(properties.getBucketName()).object(cleanKey).build());
        return true;
      } catch (Exception ignored) {
        return false;
      }
    }

    Path targetPath =
        StoragePathGuard.resolveWithin(
            Paths.get(RUSTFS_LOCAL_DATA, properties.getBucketName()), cleanKey);
    return targetPath.toFile().exists() || localFallback.exists(objectKeyOrUrl);
  }

  /** 兼容完整 URL / 存储前缀后，再对对象键做安全净化，杜绝目录穿越与非法键 */
  private String safeKey(String objectKeyOrUrl) {
    return StoragePathGuard.sanitizeObjectKey(cleanKey(objectKeyOrUrl));
  }

  private void ensureBucketExists() {
    if (!bucketChecked && minioClient != null) {
      synchronized (this) {
        if (!bucketChecked) {
          try {
            boolean exists =
                minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(properties.getBucketName()).build());
            if (!exists) {
              minioClient.makeBucket(
                  MakeBucketArgs.builder().bucket(properties.getBucketName()).build());
              log.info("rustfs 自动创建 bucket: {}", properties.getBucketName());
            }
            bucketChecked = true;
          } catch (Exception e) {
            log.error("检查/创建 rustfs bucket 异常: {}", e.getMessage(), e);
            throw BusinessException.badRequest("连接 rustfs 存储桶失败: " + e.getMessage());
          }
        }
      }
    }
  }

  private String cleanKey(String objectKeyOrUrl) {
    if (StrUtil.isBlank(objectKeyOrUrl)) {
      return "";
    }
    String clean = objectKeyOrUrl;
    String prefix = "/rustfs/" + properties.getBucketName() + "/";
    if (clean.contains(prefix)) {
      clean = clean.substring(clean.indexOf(prefix) + prefix.length());
    } else {
      String endpointPrefix = properties.getEndpoint() + "/" + properties.getBucketName() + "/";
      if (clean.contains(endpointPrefix)) {
        clean = clean.substring(clean.indexOf(endpointPrefix) + endpointPrefix.length());
      } else if (clean.startsWith("/uploads/")) {
        clean = clean.substring("/uploads/".length());
      } else if (clean.startsWith("/")) {
        clean = clean.substring(1);
      }
    }
    return clean;
  }
}
