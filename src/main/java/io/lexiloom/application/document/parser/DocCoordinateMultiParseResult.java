package io.lexiloom.application.document.parser;

import java.util.List;
import java.util.Optional;

/**
 * 复合文本/全文多坐标解析结果
 *
 * @param cleanText 彻底抹除所有坐标标记后的干净正文
 * @param boxes 提取出的所有坐标元数据列表
 */
public record DocCoordinateMultiParseResult(String cleanText, List<DocBoxMetadata> boxes) {

  public DocCoordinateMultiParseResult {
    cleanText = cleanText != null ? cleanText : "";
    boxes = boxes != null ? List.copyOf(boxes) : List.of();
  }

  /** 获取首个坐标元数据 */
  public Optional<DocBoxMetadata> findFirstBox() {
    return boxes.isEmpty() ? Optional.empty() : Optional.of(boxes.getFirst());
  }

  /** 是否提取到任何坐标元数据 */
  public boolean hasMetadata() {
    return !boxes.isEmpty();
  }
}
