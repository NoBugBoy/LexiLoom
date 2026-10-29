package io.lexiloom.application.common.storage.adapter;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.common.storage.FileStorageGateway;
import io.lexiloom.application.exception.BusinessException;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.extern.slf4j.Slf4j;

/** 本地文件存储适配器（用于未开启 OSS 时的平滑降级和本地调试） */
@Slf4j
public class LocalStorageAdapter implements FileStorageGateway {

  private static final String UPLOAD_DIR = "uploads";
  private static final String UPLOAD_PREFIX = "/uploads/";

  @Override
  public String upload(
      String objectKey, InputStream inputStream, String contentType, long contentLength) {
    if (inputStream == null) {
      throw BusinessException.badRequest("上传文件流不能为空");
    }
    try {
      String sanitizedKey = sanitizeKey(objectKey);
      Path targetPath = StoragePathGuard.resolveWithin(Paths.get(UPLOAD_DIR), sanitizedKey);
      File file = targetPath.toFile();
      FileUtil.mkdir(file.getParentFile());
      FileUtil.writeFromStream(inputStream, file);
      log.info("本地存储上传文件成功: path={}", targetPath);
      return getUrl(sanitizedKey);
    } catch (BusinessException be) {
      throw be;
    } catch (Exception e) {
      log.error("本地存储文件保存失败: objectKey={}, error={}", objectKey, e.getMessage(), e);
      throw BusinessException.badRequest("文件存储失败，请稍后重试");
    }
  }

  @Override
  public void delete(String objectKeyOrUrl) {
    try {
      Path targetPath = resolveTargetPath(objectKeyOrUrl);
      FileUtil.del(targetPath.toFile());
      log.info("本地存储删除文件成功: path={}", targetPath);
    } catch (Exception e) {
      log.warn("本地存储删除文件失败（忽略继续）: key={}, error={}", objectKeyOrUrl, e.getMessage());
    }
  }

  @Override
  public String getUrl(String objectKey) {
    return UPLOAD_PREFIX + sanitizeKey(objectKey);
  }

  @Override
  public InputStream download(String objectKeyOrUrl) {
    try {
      Path targetPath = resolveTargetPath(objectKeyOrUrl);
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
    if (StrUtil.isBlank(objectKeyOrUrl)) {
      return false;
    }
    try {
      return resolveTargetPath(objectKeyOrUrl).toFile().exists();
    } catch (Exception e) {
      return false;
    }
  }

  private Path resolveTargetPath(String objectKeyOrUrl) {
    return StoragePathGuard.resolveWithin(Paths.get(UPLOAD_DIR), sanitizeKey(objectKeyOrUrl));
  }

  private String sanitizeKey(String objectKeyOrUrl) {
    if (StrUtil.isBlank(objectKeyOrUrl)) {
      throw BusinessException.badRequest("存储对象键不能为空");
    }
    String clean = objectKeyOrUrl.trim();
    if (clean.startsWith(UPLOAD_PREFIX)) {
      clean = clean.substring(UPLOAD_PREFIX.length());
    }
    return StoragePathGuard.sanitizeObjectKey(clean);
  }
}
