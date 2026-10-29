package io.lexiloom.application.document.parser;

import io.lexiloom.application.document.api.dto.DocBoxMetadata;

import java.util.Optional;

/**
 * 单行/单块文本坐标解析结果
 *
 * @param cleanText 彻底抹除坐标标记后的干净正文
 * @param boxMetadata 提取到的坐标元数据对象，若无匹配则为 null
 */
public record DocCoordinateParseResult(String cleanText, DocBoxMetadata boxMetadata) {

  public DocCoordinateParseResult {
    cleanText = cleanText != null ? cleanText : "";
  }

  /** 获取坐标元数据的 Optional 包装 */
  public Optional<DocBoxMetadata> findBoxMetadata() {
    return Optional.ofNullable(boxMetadata);
  }

  /** 是否提取到坐标元数据 */
  public boolean hasMetadata() {
    return boxMetadata != null;
  }
}
