package io.lexiloom.application.document.parser;

import cn.hutool.core.util.StrUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * RAGFlow DeepDoc PDF 坐标标记工具类
 *
 * <p>用于解析与清洗 DeepDoc 解析器在文本中注入的排版与 OCR 坐标标记:
 *
 * <pre>{@code
 * @@页码\tx0\tx1\ty0\ty1##
 * }</pre>
 *
 * <p>提供功能:
 *
 * <ol>
 *   <li>解析提取结构化矩形边界盒元数据 {@link DocBoxMetadata} (重组为 {@code [x0, y0, x1, y1]})；
 *   <li>从原始字符串中彻底抹掉坐标标记，得到纯净正文；
 *   <li>支持单行快速解析、多行全文批量提取、存在性检测与标记逆向格式化；
 *   <li>支持反序列化与解析 DeepDoc 微服务的完整响应报文（涵盖 {@code markdown} 与 {@code data} 数组混合模式）。
 * </ol>
 */
public final class DeepDocCoordinateUtil {

  private DeepDocCoordinateUtil() {}

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  /**
   * DeepDoc 坐标标记正则表达式: {@code @@页码\tx0\tx1\ty0\ty1##}
   *
   * <p>匹配 5 个捕获组:
   *
   * <ul>
   *   <li>group(1): page (页码，整数)
   *   <li>group(2): x0 (左边界，浮点数)
   *   <li>group(3): x1 (右边界，浮点数)
   *   <li>group(4): y0 (上边界，浮点数)
   *   <li>group(5): y1 (下边界，浮点数)
   * </ul>
   *
   * 支持制表符 {@code \t} 及常规空白符容错。
   */
  public static final Pattern COORDINATE_PATTERN =
      Pattern.compile(
          "@@(\\d+)[\\t ]+([\\d\\.]+)[\\t ]+([\\d\\.]+)[\\t ]+([\\d\\.]+)[\\t ]+([\\d\\.]+)##");

  /**
   * 解析 DeepDoc 微服务返回的完整 JSON 响应报文
   *
   * <p>兼容响应格式:
   *
   * <pre>{@code
   * {
   *   "code": 200,
   *   "filename": "码流验证工具集成方案.pdf",
   *   "markdown": "",
   *   "data": [
   *     "码流⼯具集成到S16验证@@1\t38.0\t313.3\t43.0\t66.7##\n...",
   *     []
   *   ]
   * }
   * }</pre>
   *
   * @param jsonResponse DeepDoc 微服务的 HTTP 响应 JSON 字符串
   * @return 解析聚合结果对象，包含清洗后的 markdown、全文检索 rawText、坐标列表与细粒度 chunks
   */
  public static DeepDocParsedDocument parseResponse(String jsonResponse) {
    if (StrUtil.isBlank(jsonResponse)) {
      return new DeepDocParsedDocument(200, "", "", "", List.of(), List.of());
    }

    String trimmed = jsonResponse.trim();
    if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
      // 非 JSON 格式，当作纯文本处理
      DocCoordinateMultiParseResult multi = parseAll(trimmed);
      List<DocTextChunk> chunks = parseChunks(trimmed);
      return new DeepDocParsedDocument(
          200, "", multi.cleanText(), multi.cleanText(), multi.boxes(), chunks);
    }

    try {
      JsonNode root = OBJECT_MAPPER.readTree(trimmed);
      int code = root.hasNonNull("code") ? root.get("code").asInt(200) : 200;
      String filename = root.hasNonNull("filename") ? root.get("filename").asText() : "";
      String fileType = root.hasNonNull("file_type") ? root.get("file_type").asText() : "";
      int totalChunks = root.hasNonNull("total_chunks") ? root.get("total_chunks").asInt(0) : 0;

      List<DeepDocBlock> blocks =
          root.has("blocks") && root.get("blocks").isArray()
              ? parseBlocks(root.get("blocks"))
              : List.of();

      // 1. 若响应包含全新的结构化 chunks 数组（DeepDoc Multi-Format 格式）
      if (!blocks.isEmpty() || (root.hasNonNull("chunks") && root.get("chunks").isArray())) {
        List<DocTextChunk> chunks = new ArrayList<>();
        List<DocBoxMetadata> boxes = new ArrayList<>();
        JsonNode chunksNode = !blocks.isEmpty() ? null : root.get("chunks");

        if (!blocks.isEmpty()) {
          chunks.addAll(blocks.stream().map(DeepDocBlock::toTextChunk).toList());
          for (DeepDocBlock block : blocks) {
            if (block.boxMetadata() != null) boxes.add(block.boxMetadata());
          }
        }

        if (chunksNode != null)
          for (JsonNode cNode : chunksNode) {
            if (!cNode.isObject()) {
              continue;
            }
            String chunkId = cNode.hasNonNull("chunk_id") ? cNode.get("chunk_id").asText() : "";
            String content =
                cNode.hasNonNull("content")
                    ? cNode.get("content").asText()
                    : cNode.hasNonNull("text") ? cNode.get("text").asText() : "";
            String type = cNode.hasNonNull("type") ? cNode.get("type").asText() : "text";
            String anchor = cNode.hasNonNull("anchor") ? cNode.get("anchor").asText() : null;
            Integer page = cNode.hasNonNull("page") ? cNode.get("page").asInt() : null;

            DocBoxMetadata boxMeta = null;
            JsonNode bboxNode = cNode.hasNonNull("bbox") ? cNode.get("bbox") : cNode.get("box");
            if (bboxNode != null && bboxNode.isArray() && bboxNode.size() == 4) {
              double x0 = bboxNode.get(0).asDouble();
              double y0 = bboxNode.get(1).asDouble();
              double x1 = bboxNode.get(2).asDouble();
              double y1 = bboxNode.get(3).asDouble();
              int boxPage = page != null ? page : 1;
              boxMeta = DocBoxMetadata.of(boxPage, x0, y0, x1, y1);
              boxes.add(boxMeta);
            } else if (containsCoordinate(content)) {
              DocCoordinateParseResult parsed = parse(content);
              content = parsed.cleanText();
              if (parsed.hasMetadata()) {
                boxMeta = parsed.boxMetadata();
                boxes.add(boxMeta);
                if (page == null) {
                  page = boxMeta.page();
                }
              }
            }

            chunks.add(new DocTextChunk(chunkId, content, type, page, boxMeta, anchor));
          }

        // 提取 Markdown：优先使用服务端直接返回的完整 markdown 文本
        String markdown = "";
        if (root.hasNonNull("markdown") && StrUtil.isNotBlank(root.get("markdown").asText())) {
          markdown = root.get("markdown").asText();
        } else {
          StringBuilder sb = new StringBuilder();
          for (DocTextChunk c : chunks) {
            if (StrUtil.isNotBlank(c.content())) {
              if (!sb.isEmpty()) {
                sb.append("\n\n");
              }
              sb.append(c.content());
            }
          }
          markdown = sb.toString();
        }

        String cleanMarkdown = clean(markdown);
        String rawText = DocumentContentCleaner.extractRawText(cleanMarkdown);
        int finalTotalChunks = totalChunks > 0 ? totalChunks : chunks.size();

        return new DeepDocParsedDocument(
            code,
            filename,
            fileType,
            finalTotalChunks,
            cleanMarkdown,
            rawText,
            boxes,
            chunks,
            blocks);
      }

      // 2. 兼容历史/降级格式：从 markdown 或 data 数组提取
      String rawContent = extractContentFromJsonNode(root);

      // 按行/段落解析结构化 Chunks（每个 Chunk 保留其对应的坐标）
      List<DocTextChunk> chunks = parseChunks(rawContent);

      // 提取所有坐标并彻底清洗出纯净正文
      DocCoordinateMultiParseResult multi = parseAll(rawContent);
      String cleanMarkdown = multi.cleanText();
      String rawText = DocumentContentCleaner.extractRawText(cleanMarkdown);

      return new DeepDocParsedDocument(
          code, filename, fileType, chunks.size(), cleanMarkdown, rawText, multi.boxes(), chunks);
    } catch (Exception e) {
      // 容错：若 JSON 解析异常，退化为普通字符串解析
      DocCoordinateMultiParseResult multi = parseAll(trimmed);
      List<DocTextChunk> chunks = parseChunks(trimmed);
      return new DeepDocParsedDocument(
          500, "", "", 0, multi.cleanText(), multi.cleanText(), multi.boxes(), chunks);
    }
  }

  private static List<DeepDocBlock> parseBlocks(JsonNode blocksNode) {
    List<DeepDocBlock> blocks = new ArrayList<>();
    int fallbackSequence = 0;
    for (JsonNode node : blocksNode) {
      if (!node.isObject()) continue;
      String id =
          node.hasNonNull("block_id")
              ? node.get("block_id").asText()
              : node.hasNonNull("chunk_id")
                  ? node.get("chunk_id").asText()
                  : "block_" + fallbackSequence;
      int sequence = node.hasNonNull("sequence") ? node.get("sequence").asInt() : fallbackSequence;
      String type =
          node.hasNonNull("block_type")
              ? node.get("block_type").asText()
              : node.hasNonNull("type") ? node.get("type").asText() : "text";
      String content =
          node.hasNonNull("content")
              ? node.get("content").asText()
              : node.hasNonNull("text") ? node.get("text").asText() : "";
      Integer page = node.hasNonNull("page") ? node.get("page").asInt() : null;
      DocBoxMetadata box = null;
      JsonNode bbox = node.has("bbox") ? node.get("bbox") : node.get("box");
      if (bbox != null && bbox.isArray() && bbox.size() == 4) {
        box =
            DocBoxMetadata.of(
                page != null ? page : 1,
                bbox.get(0).asDouble(),
                bbox.get(1).asDouble(),
                bbox.get(2).asDouble(),
                bbox.get(3).asDouble());
        if (page == null) page = box.page();
      }
      String anchor = node.hasNonNull("anchor") ? node.get("anchor").asText() : null;
      String source = node.hasNonNull("source") ? node.get("source").asText() : "deepdoc";
      blocks.add(new DeepDocBlock(id, sequence, type, content, page, box, anchor, source));
      fallbackSequence++;
    }
    return List.copyOf(blocks);
  }

  /**
   * 将多行文本或段落拆分为细粒度文本块列表，每个块包含纯文本和其绑定的坐标元数据
   *
   * @param rawText 原始包含坐标标记的文本
   * @return 文本块列表
   */
  public static List<DocTextChunk> parseChunks(String rawText) {
    if (StrUtil.isBlank(rawText)) {
      return List.of();
    }
    List<DocTextChunk> chunks = new ArrayList<>();
    String[] lines = rawText.split("\n");
    for (String line : lines) {
      if (StrUtil.isBlank(line)) {
        continue;
      }
      DocCoordinateParseResult parsed = parse(line);
      chunks.add(new DocTextChunk(parsed.cleanText(), parsed.boxMetadata()));
    }
    return List.copyOf(chunks);
  }

  /**
   * 解析单行文本：提取首个坐标元数据并抹除所有坐标标记
   *
   * @param rawLine 包含坐标标记的原始文本行
   * @return 包含干净文本和对应坐标元数据的解析结果
   */
  public static DocCoordinateParseResult parse(String rawLine) {
    if (StrUtil.isBlank(rawLine)) {
      return new DocCoordinateParseResult("", null);
    }
    Matcher matcher = COORDINATE_PATTERN.matcher(rawLine);
    DocBoxMetadata metadata = null;
    if (matcher.find()) {
      metadata = extractMetadataFromMatcher(matcher);
    }
    String cleanText = matcher.replaceAll("").trim();
    return new DocCoordinateParseResult(cleanText, metadata);
  }

  /**
   * 解析全文/复合文本：提取全文所有坐标元数据，并彻底清洗掉文本中的全部坐标标记
   *
   * @param rawText 包含多个坐标标记的全文或段落
   * @return 包含干净文本和全部坐标元数据列表的多结果对象
   */
  public static DocCoordinateMultiParseResult parseAll(String rawText) {
    if (StrUtil.isBlank(rawText)) {
      return new DocCoordinateMultiParseResult("", List.of());
    }
    Matcher matcher = COORDINATE_PATTERN.matcher(rawText);
    List<DocBoxMetadata> boxes = new ArrayList<>();
    while (matcher.find()) {
      boxes.add(extractMetadataFromMatcher(matcher));
    }
    String cleanText = matcher.replaceAll("").trim();
    return new DocCoordinateMultiParseResult(cleanText, boxes);
  }

  /**
   * 纯文本清洗：从原始字符串中彻底抹掉所有坐标标记，得到纯净文本
   *
   * @param rawText 原始文本
   * @return 抹除坐标标记并去除首尾空白后的干净文本
   */
  public static String clean(String rawText) {
    if (StrUtil.isBlank(rawText)) {
      return "";
    }
    return COORDINATE_PATTERN.matcher(rawText).replaceAll("").trim();
  }

  /**
   * 提取首个坐标元数据
   *
   * @param rawText 包含坐标标记的文本
   * @return 首个坐标元数据 Optional
   */
  public static Optional<DocBoxMetadata> extractFirstBox(String rawText) {
    if (StrUtil.isBlank(rawText)) {
      return Optional.empty();
    }
    Matcher matcher = COORDINATE_PATTERN.matcher(rawText);
    if (matcher.find()) {
      return Optional.of(extractMetadataFromMatcher(matcher));
    }
    return Optional.empty();
  }

  /**
   * 提取全文所有坐标元数据列表
   *
   * @param rawText 包含坐标标记的文本
   * @return 坐标元数据不可变列表
   */
  public static List<DocBoxMetadata> extractAllBoxes(String rawText) {
    if (StrUtil.isBlank(rawText)) {
      return List.of();
    }
    Matcher matcher = COORDINATE_PATTERN.matcher(rawText);
    List<DocBoxMetadata> boxes = new ArrayList<>();
    while (matcher.find()) {
      boxes.add(extractMetadataFromMatcher(matcher));
    }
    return List.copyOf(boxes);
  }

  /**
   * 提取坐标并转为结构化 Map 元数据字典（与 Python 输出完全一致: {@code {"page": 1, "box": [36.3, 271.0, 548.0, 286.0]}}）
   *
   * @param rawText 原始文本
   * @return 元数据 Map Optional
   */
  public static Optional<Map<String, Object>> extractBoxMetadataMap(String rawText) {
    return extractFirstBox(rawText).map(DocBoxMetadata::toMap);
  }

  /**
   * 检查文本中是否包含 DeepDoc 坐标标记
   *
   * @param rawText 待检测文本
   * @return true 若包含坐标标记
   */
  public static boolean containsCoordinate(String rawText) {
    if (StrUtil.isBlank(rawText)) {
      return false;
    }
    return COORDINATE_PATTERN.matcher(rawText).find();
  }

  /**
   * 格式化生成 DeepDoc 标准坐标标记字符串: {@code @@页码\tx0\tx1\ty0\ty1##}
   *
   * @param page 页码
   * @param x0 左边界
   * @param x1 右边界
   * @param y0 上边界
   * @param y1 下边界
   * @return 格式化后的坐标标签字符串
   */
  public static String formatCoordinateTag(int page, double x0, double x1, double y0, double y1) {
    return String.format(Locale.ROOT, "@@%d\t%.1f\t%.1f\t%.1f\t%.1f##", page, x0, x1, y0, y1);
  }

  /**
   * 依据 {@link DocBoxMetadata} 逆向格式化为 DeepDoc 坐标标记
   *
   * @param metadata 坐标元数据对象
   * @return 格式化后的坐标标签字符串，若传入 null 则返回空字符串
   */
  public static String formatCoordinateTag(DocBoxMetadata metadata) {
    if (metadata == null) {
      return "";
    }
    // metadata.box 存储顺序为 [x0, y0, x1, y1]
    // DeepDoc 字符串 tag 格式顺序为 @@page\tx0\tx1\ty0\ty1##
    return formatCoordinateTag(
        metadata.page(), metadata.x0(), metadata.x1(), metadata.y0(), metadata.y1());
  }

  private static DocBoxMetadata extractMetadataFromMatcher(Matcher matcher) {
    int page = Integer.parseInt(matcher.group(1));
    double x0 = Double.parseDouble(matcher.group(2));
    double x1 = Double.parseDouble(matcher.group(3));
    double y0 = Double.parseDouble(matcher.group(4));
    double y1 = Double.parseDouble(matcher.group(5));
    // 按照 [x0, y0, x1, y1] 顺序重组为标准 box 矩形 [left, top, right, bottom]
    return DocBoxMetadata.of(page, x0, y0, x1, y1);
  }

  private static String extractContentFromJsonNode(JsonNode root) {
    // 1. 优先使用 markdown 字段（若非空）
    if (root.hasNonNull("markdown")) {
      String md = root.get("markdown").asText();
      if (StrUtil.isNotBlank(md)) {
        return md;
      }
    }

    // 2. 从 data 节点提取
    if (root.hasNonNull("data")) {
      JsonNode dataNode = root.get("data");
      if (dataNode.isArray()) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : dataNode) {
          if (item.isTextual()) {
            String text = item.asText();
            if (StrUtil.isNotBlank(text)) {
              if (!sb.isEmpty()) {
                sb.append("\n\n");
              }
              sb.append(text);
            }
          } else if (item.isObject()) {
            String itemText = extractItemText(item);
            if (StrUtil.isNotBlank(itemText)) {
              if (!sb.isEmpty()) {
                sb.append("\n\n");
              }
              sb.append(itemText);
            }
          }
        }
        if (!sb.isEmpty()) {
          return sb.toString();
        }
      } else if (dataNode.isTextual()) {
        return dataNode.asText();
      }
    }

    // 3. 兜底检查 text 或 content 字段
    if (root.hasNonNull("text") && StrUtil.isNotBlank(root.get("text").asText())) {
      return root.get("text").asText();
    }
    if (root.hasNonNull("content") && StrUtil.isNotBlank(root.get("content").asText())) {
      return root.get("content").asText();
    }

    return "";
  }

  private static String extractItemText(JsonNode item) {
    if (item.hasNonNull("html") && StrUtil.isNotBlank(item.get("html").asText())) {
      return item.get("html").asText();
    }
    if (item.hasNonNull("text") && StrUtil.isNotBlank(item.get("text").asText())) {
      return item.get("text").asText();
    }
    if (item.hasNonNull("markdown") && StrUtil.isNotBlank(item.get("markdown").asText())) {
      return item.get("markdown").asText();
    }
    if (item.hasNonNull("content") && StrUtil.isNotBlank(item.get("content").asText())) {
      return item.get("content").asText();
    }
    return "";
  }
}
