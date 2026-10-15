package io.lexiloom.application.document.parser;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DeepDoc 响应反序列化与解析聚合结果 (VO)
 *
 * @param code 响应状态码（如 200）
 * @param filename 原始文件名
 * @param fileType 文件格式类型（如 pdf, docx, pptx, xlsx, txt 等）
 * @param totalChunks 切片总数量
 * @param markdown 剥离坐标标记与垃圾内嵌图后的纯净 Markdown 文本
 * @param rawText 供全文检索与分词使用的纯文本
 * @param boxes 全文所有元素对应的坐标边界盒元数据列表
 * @param chunks 细粒度结构化切片列表
 * @param blocks 版面解析中间块列表，供检索切块器二次恢复结构
 */
public record DeepDocParsedDocument(
    int code,
    String filename,
    String fileType,
    int totalChunks,
    String markdown,
    String rawText,
    List<DocBoxMetadata> boxes,
    List<DocTextChunk> chunks,
    List<DeepDocBlock> blocks) {

  public DeepDocParsedDocument {
    filename = filename != null ? filename : "";
    fileType = fileType != null ? fileType : "";
    markdown = markdown != null ? markdown : "";
    rawText = rawText != null ? rawText : "";
    boxes = boxes != null ? List.copyOf(boxes) : List.of();
    chunks = chunks != null ? List.copyOf(chunks) : List.of();
    blocks = blocks != null ? List.copyOf(blocks) : List.of();
    if (totalChunks <= 0) {
      totalChunks = chunks.size();
    }
  }

  /** 兼容旧版构造方法（无 fileType 与 totalChunks） */
  public DeepDocParsedDocument(
      int code,
      String filename,
      String markdown,
      String rawText,
      List<DocBoxMetadata> boxes,
      List<DocTextChunk> chunks) {
    this(
        code,
        filename,
        "",
        chunks != null ? chunks.size() : 0,
        markdown,
        rawText,
        boxes,
        chunks,
        chunks == null
            ? List.of()
            : chunks.stream()
                .map(
                    c ->
                        new DeepDocBlock(
                            c.chunkId(),
                            0,
                            c.type(),
                            c.content(),
                            c.page(),
                            c.boxMetadata(),
                            c.anchor(),
                            "legacy"))
                .toList());
  }

  public DeepDocParsedDocument(
      int code,
      String filename,
      String fileType,
      int totalChunks,
      String markdown,
      String rawText,
      List<DocBoxMetadata> boxes,
      List<DocTextChunk> chunks) {
    this(
        code,
        filename,
        fileType,
        totalChunks,
        markdown,
        rawText,
        boxes,
        chunks,
        chunks == null
            ? List.of()
            : chunks.stream()
                .map(
                    c ->
                        new DeepDocBlock(
                            c.chunkId(),
                            0,
                            c.type(),
                            c.content(),
                            c.page(),
                            c.boxMetadata(),
                            c.anchor(),
                            "legacy"))
                .toList());
  }

  /** 获取首个坐标元数据 */
  public Optional<DocBoxMetadata> findFirstBox() {
    return boxes.isEmpty() ? Optional.empty() : Optional.of(boxes.getFirst());
  }

  /** 是否提取到任何坐标元数据 */
  public boolean hasBoxes() {
    return !boxes.isEmpty();
  }

  /** 获取所有坐标的 Map 列表表示: [ {"page": 1, "box": [...]}, ... ] */
  public List<Map<String, Object>> boxMaps() {
    return boxes.stream().map(DocBoxMetadata::toMap).toList();
  }

  /** 获取所有细粒度 Chunk 的 Map 列表表示 */
  public List<Map<String, Object>> chunkMaps() {
    return chunks.stream().map(DocTextChunk::toMap).toList();
  }

  public List<Map<String, Object>> blockMaps() {
    return blocks.stream().map(DeepDocBlock::toMap).toList();
  }
}
