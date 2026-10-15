package io.lexiloom.application.document.pipeline;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.document.parser.DocParserEngineEnum;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
import io.lexiloom.application.exception.BusinessException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/** 文档导入命令参数对象（不可变 Java Record） */
public record DocumentImportCommand(
    Long workSpaceId,
    Long documentId,
    Long categoryId,
    String originalFileName,
    byte[] fileBytes,
    String contentType,
    DocParserEngineEnum preferredEngine,
    boolean extractPdfToDoc,
    WorkSpaceAclEnum visibility,
    Map<String, Object> extraOptions) {

  public DocumentImportCommand {
    Objects.requireNonNull(workSpaceId, "工作区ID不能为空");
    if (StrUtil.isBlank(originalFileName)) {
      throw BusinessException.badRequest("原始文件名不能为空");
    }
    if (fileBytes == null || fileBytes.length == 0) {
      throw BusinessException.badRequest("导入文件内容不能为空");
    }
    categoryId = (categoryId == null) ? 0L : categoryId;
    preferredEngine = (preferredEngine == null) ? DocParserEngineEnum.AUTO : preferredEngine;
    visibility = (visibility == null) ? WorkSpaceAclEnum.INTERNAL : visibility;
    extraOptions =
        (extraOptions == null) ? Collections.emptyMap() : Collections.unmodifiableMap(extraOptions);
  }

  /** 兼容旧版不带 documentId 构造器（默认新建导入） */
  public DocumentImportCommand(
      Long workSpaceId,
      Long categoryId,
      String originalFileName,
      byte[] fileBytes,
      String contentType,
      DocParserEngineEnum preferredEngine,
      boolean extractPdfToDoc,
      WorkSpaceAclEnum visibility,
      Map<String, Object> extraOptions) {
    this(
        workSpaceId,
        null,
        categoryId,
        originalFileName,
        fileBytes,
        contentType,
        preferredEngine,
        extractPdfToDoc,
        visibility,
        extraOptions);
  }

  /** 快捷工厂：通过字节数组构建 */
  public static DocumentImportCommand ofBytes(
      Long workSpaceId,
      Long categoryId,
      String originalFileName,
      byte[] fileBytes,
      String contentType) {
    return new DocumentImportCommand(
        workSpaceId,
        categoryId,
        originalFileName,
        fileBytes,
        contentType,
        DocParserEngineEnum.AUTO,
        false,
        WorkSpaceAclEnum.INTERNAL,
        Collections.emptyMap());
  }

  /** 快捷工厂：通过输入流构建 */
  public static DocumentImportCommand ofStream(
      Long workSpaceId,
      Long categoryId,
      String originalFileName,
      InputStream inputStream,
      String contentType) {
    Objects.requireNonNull(inputStream, "文件输入流不能为空");
    byte[] bytes = IoUtil.readBytes(inputStream);
    return ofBytes(workSpaceId, categoryId, originalFileName, bytes, contentType);
  }

  /** 提取文件扩展名 */
  public String fileExtension() {
    int idx = originalFileName.lastIndexOf('.');
    return (idx >= 0) ? originalFileName.substring(idx + 1).toLowerCase() : "";
  }

  /** 获取文件大小 */
  public long fileSize() {
    return fileBytes != null ? fileBytes.length : 0L;
  }
}
