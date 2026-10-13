package com.knowflow.application.common.storage.adapter;

import com.knowflow.application.exception.BusinessException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 对象存储键安全工具
 *
 * <p>上传文件名来源于客户端，属于不可信输入。本工具负责：
 *
 * <ul>
 *   <li>{@link #sanitizeObjectKey(String)}：归一化路径分隔符并剔除 {@code .} / {@code ..} 等危险路径段；
 *   <li>{@link #resolveWithin(Path, String)}：在落盘前做归一化 + 根目录包含性校验，防止目录穿越写出存储根目录。
 * </ul>
 */
final class StoragePathGuard {

  private StoragePathGuard() {}

  /** 净化对象存储键：统一分隔符、丢弃空段与 {@code .}/{@code ..} 段，避免污染存储键与触发目录穿越 */
  static String sanitizeObjectKey(String objectKey) {
    if (objectKey == null || objectKey.isBlank()) {
      throw BusinessException.badRequest("存储对象键不能为空");
    }
    List<String> segments = new ArrayList<>();
    for (String segment : objectKey.replace('\\', '/').split("/")) {
      if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
        continue;
      }
      segments.add(segment);
    }
    if (segments.isEmpty()) {
      throw BusinessException.badRequest("非法的存储对象键");
    }
    return String.join("/", segments);
  }

  /** 将对象键安全解析为存储根目录内的绝对路径；归一化后越出根目录则直接拒绝 */
  static Path resolveWithin(Path rootDir, String objectKey) {
    Path normalizedRoot = rootDir.toAbsolutePath().normalize();
    Path target = normalizedRoot.resolve(sanitizeObjectKey(objectKey)).normalize();
    if (!target.startsWith(normalizedRoot)) {
      throw BusinessException.badRequest("非法的存储对象键");
    }
    return target;
  }
}
