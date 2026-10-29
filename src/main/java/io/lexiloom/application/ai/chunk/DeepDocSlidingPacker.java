package io.lexiloom.application.ai.chunk;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.chunk.DeepDocOutlineParser.LogicalSection;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * 语义边界感知贪心装箱与滑动重叠切分器 (DeepDocSlidingPacker)
 *
 * <p>负责按 Token 维度执行章节语义聚合、超长段落标点滑动重叠切分、超长 HTML 表格保表头切块及向量标签去噪。
 */
@Slf4j
final class DeepDocSlidingPacker {

  private static final Pattern SENTENCE_SPLIT_PATTERN =
      Pattern.compile("(?<=[。！？；!?;]|\\.(?!\\d))\\s*");
  private static final Pattern HTML_TR_PATTERN =
      Pattern.compile("<tr\\b[^>]*>[\\s\\S]*?</tr>", Pattern.CASE_INSENSITIVE);
  private static final Pattern HTML_TAG_STRIP_PATTERN = Pattern.compile("<[^>]+>");
  private static final Pattern MULTI_WHITESPACE_PATTERN = Pattern.compile("\\s+");

  private DeepDocSlidingPacker() {}

  static List<RawChunkItem> chunkAndOverlap(
      List<LogicalSection> sections, int targetTokens, int overlapTokens, int maxLimit) {
    List<RawChunkItem> items = new ArrayList<>();
    StringBuilder currentBuffer = new StringBuilder();
    String currentHeaderPath = null;
    String lastOverlapTail = "";

    for (LogicalSection sec : sections) {
      if ("table".equals(sec.type()) || "figure".equals(sec.type()) || "code".equals(sec.type())) {
        if (!currentBuffer.isEmpty()) {
          String flushedContent = truncateToLimit(currentBuffer.toString().trim(), maxLimit);
          items.add(new RawChunkItem(flushedContent, currentHeaderPath, "text", null, null));
          lastOverlapTail = extractTailOverlap(flushedContent, overlapTokens);
          currentBuffer.setLength(0);
        }
        if ("table".equals(sec.type())) {
          if (sec.content().contains("<table") && estimateTokens(sec.content()) > targetTokens) {
            chunkLargeHtmlTable(items, sec.content(), sec.headerPath(), targetTokens);
          } else {
            items.add(new RawChunkItem(sec.content(), sec.headerPath(), "table", null, null));
          }
        } else {
          items.add(
              new RawChunkItem(
                  truncateToLimit(sec.content(), maxLimit),
                  sec.headerPath(),
                  sec.type(),
                  null,
                  null));
        }
        lastOverlapTail = "";
        continue;
      }

      int secTokens = estimateTokens(sec.content());

      if (secTokens > targetTokens) {
        if (!currentBuffer.isEmpty()) {
          String flushedContent = truncateToLimit(currentBuffer.toString().trim(), maxLimit);
          items.add(new RawChunkItem(flushedContent, currentHeaderPath, "text", null, null));
          currentBuffer.setLength(0);
        }
        List<RawChunkItem> subChunks =
            splitLongSectionWithOverlap(
                sec.content(), sec.headerPath(), targetTokens, overlapTokens, maxLimit);
        if (!subChunks.isEmpty()) {
          items.addAll(subChunks);
          lastOverlapTail = extractTailOverlap(subChunks.getLast().content(), overlapTokens);
        }
        continue;
      }

      boolean isBranchSwitch =
          currentHeaderPath != null
              && !isCompatibleSection(currentHeaderPath, sec.headerPath())
              && !currentBuffer.isEmpty()
              && estimateTokens(currentBuffer.toString()) >= 15;

      int currentTokens = estimateTokens(currentBuffer.toString());
      boolean isOverflow = (currentTokens + secTokens > targetTokens);

      if ((isBranchSwitch || isOverflow) && !currentBuffer.isEmpty()) {
        String flushedContent = truncateToLimit(currentBuffer.toString().trim(), maxLimit);
        items.add(new RawChunkItem(flushedContent, currentHeaderPath, "text", null, null));
        lastOverlapTail = isOverflow ? extractTailOverlap(flushedContent, overlapTokens) : "";
        currentBuffer.setLength(0);
      }

      if (currentBuffer.isEmpty()) {
        currentHeaderPath = sec.headerPath();
        if (StrUtil.isNotBlank(lastOverlapTail) && !sec.content().startsWith(lastOverlapTail)) {
          currentBuffer.append(lastOverlapTail).append("\n\n");
        }
      } else {
        currentBuffer.append("\n\n");
      }
      currentBuffer.append(sec.content());
      currentHeaderPath = sec.headerPath();
    }

    if (!currentBuffer.isEmpty()) {
      String flushedContent = truncateToLimit(currentBuffer.toString().trim(), maxLimit);
      items.add(new RawChunkItem(flushedContent, currentHeaderPath, "text", null, null));
    }

    return items;
  }

  private static boolean isCompatibleSection(
      @Nullable String currentPath, @Nullable String nextPath) {
    if (currentPath == null || nextPath == null || currentPath.equals(nextPath)) {
      return true;
    }
    if (nextPath.startsWith(currentPath) || currentPath.startsWith(nextPath)) {
      return true;
    }
    return extractMajorBranch(currentPath).equals(extractMajorBranch(nextPath));
  }

  private static String extractMajorBranch(@Nullable String path) {
    if (path == null) {
      return "";
    }
    String[] parts = path.split(" / ");
    if (parts.length <= 2) {
      return path;
    }
    return parts[0] + " / " + parts[1];
  }

  private static String extractTailOverlap(String text, int overlapTokens) {
    if (StrUtil.isBlank(text) || overlapTokens <= 0) {
      return "";
    }
    String[] sentences = SENTENCE_SPLIT_PATTERN.split(text.trim());
    List<String> validSentences = new ArrayList<>();
    for (String s : sentences) {
      String tr = s.trim();
      if (!tr.isEmpty()) {
        validSentences.add(tr);
      }
    }
    if (validSentences.isEmpty()) {
      return "";
    }
    if (validSentences.size() == 1) {
      return validSentences.getFirst();
    }

    List<String> overlapList = new ArrayList<>();
    int accumulatedTokens = 0;
    for (int i = validSentences.size() - 1; i >= 0; i--) {
      String sent = validSentences.get(i);
      int sentTokens = estimateTokens(sent);
      if (accumulatedTokens + sentTokens > overlapTokens && !overlapList.isEmpty()) {
        break;
      }
      overlapList.addFirst(sent);
      accumulatedTokens += sentTokens;
    }
    return String.join(" ", overlapList).trim();
  }

  private static List<RawChunkItem> splitLongSectionWithOverlap(
      String text, String headerPath, int targetTokens, int overlapTokens, int maxLimit) {
    String safeText = truncateToLimit(text.trim(), maxLimit);
    String[] sentences = SENTENCE_SPLIT_PATTERN.split(safeText);
    List<String> validSentences = new ArrayList<>();
    for (String s : sentences) {
      String tr = s.trim();
      if (!tr.isEmpty()) {
        validSentences.add(tr);
      }
    }

    if (validSentences.isEmpty()) {
      return List.of(
          new RawChunkItem(truncateToLimit(text, maxLimit), headerPath, "text", null, null));
    }

    List<RawChunkItem> result = new ArrayList<>();
    int startIdx = 0;
    while (startIdx < validSentences.size()) {
      StringBuilder window = new StringBuilder();
      int windowTokens = 0;
      int endIdx = startIdx;

      while (endIdx < validSentences.size()) {
        String sent = validSentences.get(endIdx);
        int sentTokens = estimateTokens(sent);
        if (windowTokens + sentTokens > targetTokens && windowTokens > 0) {
          break;
        }
        if (!window.isEmpty()) {
          window.append(" ");
        }
        window.append(sent);
        windowTokens += sentTokens;
        endIdx++;
      }

      String chunkContent = truncateToLimit(window.toString().trim(), maxLimit);
      result.add(new RawChunkItem(chunkContent, headerPath, "text", null, null));

      if (endIdx >= validSentences.size()) {
        break;
      }

      int nextStart = endIdx;
      int accumulatedOverlapTokens = 0;
      while (nextStart > startIdx + 1 && accumulatedOverlapTokens < overlapTokens) {
        nextStart--;
        accumulatedOverlapTokens += estimateTokens(validSentences.get(nextStart));
      }
      startIdx = nextStart;
    }
    return result;
  }

  private static void chunkLargeHtmlTable(
      List<RawChunkItem> items, String tableContent, String headerPath, int targetTokens) {
    String leadIn = "";
    String tableHtml = tableContent;
    int tableStartIdx = tableContent.indexOf("<table");
    if (tableStartIdx > 0) {
      leadIn = tableContent.substring(0, tableStartIdx).trim();
      tableHtml = tableContent.substring(tableStartIdx);
    }

    Matcher rowMatcher = HTML_TR_PATTERN.matcher(tableHtml);
    List<String> rows = new ArrayList<>();
    while (rowMatcher.find()) {
      rows.add(rowMatcher.group(0));
    }

    if (rows.size() <= 2) {
      items.add(new RawChunkItem(tableContent, headerPath, "table", null, null));
      return;
    }

    String headerRow = rows.getFirst();
    StringBuilder currentBatch = new StringBuilder();
    int currentTokens = estimateTokens(headerRow);
    boolean isFirstBatch = true;

    for (int i = 1; i < rows.size(); i++) {
      String row = rows.get(i);
      int rowTokens = estimateTokens(row);
      if (currentTokens + rowTokens > targetTokens && !currentBatch.isEmpty()) {
        String subTable = "<table>\n" + headerRow + "\n" + currentBatch + "</table>";
        String contentToSave =
            (isFirstBatch && StrUtil.isNotBlank(leadIn)) ? (leadIn + "\n\n" + subTable) : subTable;
        items.add(new RawChunkItem(contentToSave, headerPath, "table", null, null));
        currentBatch.setLength(0);
        currentTokens = estimateTokens(headerRow);
        isFirstBatch = false;
      }
      currentBatch.append(row).append("\n");
      currentTokens += rowTokens;
    }

    if (!currentBatch.isEmpty()) {
      String subTable = "<table>\n" + headerRow + "\n" + currentBatch + "</table>";
      String contentToSave =
          (isFirstBatch && StrUtil.isNotBlank(leadIn)) ? (leadIn + "\n\n" + subTable) : subTable;
      items.add(new RawChunkItem(contentToSave, headerPath, "table", null, null));
    }
  }

  static String stripHtmlForEmbedding(String content) {
    if (StrUtil.isBlank(content)) {
      return "";
    }
    if (content.contains("<") && content.contains(">")) {
      String withoutTags = HTML_TAG_STRIP_PATTERN.matcher(content).replaceAll(" ");
      return MULTI_WHITESPACE_PATTERN.matcher(withoutTags).replaceAll(" ").trim();
    }
    return content;
  }

  static int estimateTokens(String text) {
    if (StrUtil.isBlank(text)) {
      return 0;
    }
    int cjkCount = 0;
    int nonCjkCount = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
        cjkCount++;
      } else {
        nonCjkCount++;
      }
    }
    return (int) Math.ceil(cjkCount * 1.2 + nonCjkCount * 0.3);
  }

  private static String truncateToLimit(String text, int maxLimit) {
    if (text.length() > maxLimit) {
      log.warn("【DeepDocContextEnricher】原子切块字符数超限 ({} > {})，执行安全截断保护", text.length(), maxLimit);
      return text.substring(0, maxLimit);
    }
    return text;
  }

  record RawChunkItem(
      String content,
      @Nullable String headerPath,
      String chunkType,
      @Nullable Integer page,
      @Nullable String anchor) {}
}
