package com.knowflow.application.common.storage.adapter;

import cn.hutool.core.io.FileUtil;
import com.knowflow.application.common.storage.FileStorageGateway;
import com.knowflow.application.exception.BusinessException;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.extern.slf4j.Slf4j;

/** 本地文件存储适配器（用于未开启 OSS 时的平滑降级和本地调试） */
@Slf4j
public class LocalStorageAdapter implements FileStorageGateway {

  private static final String UPLOAD_DIR = "uploads";

  @Override
  public String upload(
      String objectKey, InputStream inputStream, String contentType, long contentLength) {
    try {
      Path targetPath = StoragePathGuard.resolveWithin(Paths.get(UPLOAD_DIR), objectKey);
      File file = targetPath.toFile();
      FileUtil.mkdir(file.getParentFile());
      FileUtil.writeFromStream(inputStream, file);
      log.info("本地存储上传文件成功: path={}", targetPath);
      return getUrl(objectKey);
    } catch (Exception e) {
      log.error("本地存储文件保存失败: objectKey={}, error={}", objectKey, e.getMessage(), e);
      throw BusinessException.badRequest("文件存储失败，请稍后重试");
    }
  }

  @Override
  public void delete(String objectKeyOrUrl) {
    try {
      String cleanKey = objectKeyOrUrl;
      if (cleanKey.startsWith("/uploads/")) {
        cleanKey = cleanKey.substring("/uploads/".length());
      }
      Path targetPath = StoragePathGuard.resolveWithin(Paths.get(UPLOAD_DIR), cleanKey);
      FileUtil.del(targetPath.toFile());
      log.info("本地存储删除文件成功: path={}", targetPath);
    } catch (Exception e) {
      log.warn("本地存储删除文件失败（忽略继续）: key={}, error={}", objectKeyOrUrl, e.getMessage());
    }
  }

  @Override
  public String getUrl(String objectKey) {
    String cleanKey = objectKey.startsWith("/") ? objectKey.substring(1) : objectKey;
    return "/uploads/" + cleanKey;
  }

  @Override
  public InputStream download(String objectKeyOrUrl) {
    try {
      String cleanKey = objectKeyOrUrl;
      if (cleanKey.startsWith("/uploads/")) {
        cleanKey = cleanKey.substring("/uploads/".length());
      }
      Path targetPath = StoragePathGuard.resolveWithin(Paths.get(UPLOAD_DIR), cleanKey);
      File file = targetPath.toFile();
      if (!file.exists()) {
        throw BusinessException.notFound("存储文件不存在: " + objectKeyOrUrl);
      }
      return FileUtil.getInputStream(file);
    } catch (BusinessException be) {
      throw be;
    } catch (Exception e) {
      log.error("本地存储文件读取失败: key={}, error={}", objectKeyOrUrl, e.getMessage(), e);
      throw BusinessException.badRequest("读取文件失败");
    }
  }

  @Override
  public boolean exists(String objectKeyOrUrl) {
    String cleanKey = objectKeyOrUrl;
    if (cleanKey.startsWith("/uploads/")) {
      cleanKey = cleanKey.substring("/uploads/".length());
    }
    Path targetPath = StoragePathGuard.resolveWithin(Paths.get(UPLOAD_DIR), cleanKey);
    return targetPath.toFile().exists();
  }
}
