package io.lexiloom.application.document.parser;

import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import org.springframework.util.StringUtils;

/** 文档解析通用命令对象（不可变参数对象） */
public record DocParseCommand(
    InputStream contentStream,
    String storagePath,
    String fileName,
    String fileExtension,
    String mimeType,
    DocParserEngineEnum preferredEngine,
    Map<String, Object> extraOptions) {

  public DocParseCommand {
    if (!StringUtils.hasText(fileExtension) && StringUtils.hasText(fileName)) {
      int idx = fileName.lastIndexOf('.');
      fileExtension = (idx >= 0) ? fileName.substring(idx + 1).toLowerCase() : "";
    } else if (fileExtension != null) {
      fileExtension = fileExtension.toLowerCase().replace(".", "");
    }
    preferredEngine = (preferredEngine == null) ? DocParserEngineEnum.AUTO : preferredEngine;
    extraOptions =
        (extraOptions == null) ? Collections.emptyMap() : Collections.unmodifiableMap(extraOptions);
  }

  public static DocParseCommand ofStream(
      InputStream inputStream, String fileName, DocParserEngineEnum preferredEngine) {
    Objects.requireNonNull(inputStream, "输入流不能为空");
    return new DocParseCommand(
        inputStream, null, fileName, null, null, preferredEngine, Collections.emptyMap());
  }

  public static DocParseCommand ofStorage(
      String storagePath, String fileName, DocParserEngineEnum preferredEngine) {
    Objects.requireNonNull(storagePath, "存储路径不能为空");
    return new DocParseCommand(
        null, storagePath, fileName, null, null, preferredEngine, Collections.emptyMap());
  }

  public static DocParseCommand of(
      InputStream inputStream,
      String storagePath,
      String fileName,
      String mimeType,
      DocParserEngineEnum preferredEngine,
      Map<String, Object> extraOptions) {
    return new DocParseCommand(
        inputStream, storagePath, fileName, null, mimeType, preferredEngine, extraOptions);
  }

  public boolean isOcrRequested() {
    return Boolean.TRUE.equals(extraOptions.get("ocr"));
  }

  public boolean isExtractImagesRequested() {
    return Boolean.TRUE.equals(extraOptions.get("extractImages"));
  }

  public boolean isSyncRequested() {
    return Boolean.TRUE.equals(extraOptions.get("sync"));
  }
}
