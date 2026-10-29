package io.lexiloom.application.ai.chunk;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.document.api.DocumentContentCleaner;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 文档结构化大纲解析器与 OCR 噪声清洗器 (DeepDocOutlineParser)
 *
 * <p>负责 Markdown/HTML/OCR 文本清洗、目录过滤、多级编号与行内标题识别、前置引导说明吸纳以及逻辑段落构建。
 */
final class DeepDocOutlineParser {

  static final String DEFAULT_FALLBACK_HEADER = "正文";

  private static final Pattern MARKDOWN_HEADER_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");
  private static final Pattern HTML_HEADER_PATTERN =
      Pattern.compile("^<h([1-6])\\b[^>]*>(.*?)</h\\1>$", Pattern.CASE_INSENSITIVE);
  private static final Pattern NUMBERED_HEADER_PATTERN =
      Pattern.compile("^(\\d+(?:\\.\\d+){0,4})[、.\\s]+(.+)$");
  private static final Pattern CJK_CHAPTER_PATTERN =
      Pattern.compile("^(?:第[一二三四五六七八九十0-9]+[章节篇部分条]|[一二三四五六七八九十]+[、.])[\\s]*([^。！？；\\n]+)$");
  private static final Pattern CJK_SUBCHAPTER_PATTERN =
      Pattern.compile("^(?:（[一二三四五六七八九十0-9]+）|\\([一二三四五六七八九十0-9]+\\))[\\s]*([^。！？；\\n]+)$");
  private static final Pattern TOC_LINE_PATTERN =
      Pattern.compile(
          "^\\s*(?:(?:第[一二三四五六七八九十百0-9]+[章篇部卷]|\\d+(?:\\.\\d+){0,5}[、.]?)\\s*)?\\S.+?(?:[\\.·…]{2,}\\s*\\d+|\\s*\\d{1,3})\\s*$");
  private static final Pattern CJK_SPACES_PATTERN =
      Pattern.compile("([\\u4e00-\\u9fa5])([ \\t　]+)([\\u4e00-\\u9fa5])");
  private static final Pattern CJK_PUNCT_SPACES_1 =
      Pattern.compile("([\\u4e00-\\u9fa5])([ \\t　]+)([，。！？；：、“”‘’（）《》【】])");
  private static final Pattern CJK_PUNCT_SPACES_2 =
      Pattern.compile("([，。！？；：、“”‘’（）《》【】])([ \\t　]+)([\\u4e00-\\u9fa5])");
  private static final Pattern BLOCK_TAG_START_PATTERN =
      Pattern.compile("(?<!\\n\\n)\\s*(<(?:table|figure|pre)[\\s>])");
  private static final Pattern BLOCK_TAG_END_PATTERN =
      Pattern.compile("(</(?:table|figure|pre)>)\\s*(?!\\n\\n)");
  private static final Pattern OCR_SPLIT_NUM_FIRST_PATTERN =
      Pattern.compile("(?m)^(\\d+(?:\\.\\d+)+)\\s*\\n+\\s*([^\\d\\n#<\\|\\s][^\\n]{1,60})$");
  private static final Pattern OCR_SPLIT_TITLE_FIRST_PATTERN =
      Pattern.compile("(?m)^([^\\d\\n#<\\|\\s][^\\n]{1,60})\\s*\\n+\\s*(\\d+(?:\\.\\d+)+)$");
  private static final Pattern BROKEN_NUM_DOT_PATTERN =
      Pattern.compile("(\\d+)\\s*\\.\\s*(\\d+)");
  private static final Pattern PURE_NUMBERED_LINE_PATTERN =
      Pattern.compile("^\\d+(?:\\.\\d+)+[、.]?$");
  private static final Pattern DIGITS_ONLY_PATTERN = Pattern.compile("^\\d+$");

  private static final List<String> ACTION_PREFIXES =
      List.of(
          "根据", "支持", "通过", "调用", "点击", "修改", "删除", "录入", "展示", "获取", "新建", "如果", "当", "需", "允许",
          "向");

  private DeepDocOutlineParser() {}

  /** 数据清洗：去除脏内嵌图片、多余空行与汉字间异常空格 */
  static String cleanMarkdownText(String markdown) {
    if (StrUtil.isBlank(markdown)) {
      return "";
    }
    String cleaned = DocumentContentCleaner.cleanEmbeddedImages(markdown);
    cleaned = DocumentContentCleaner.cleanCoordinates(cleaned);

    Matcher matcher = CJK_SPACES_PATTERN.matcher(cleaned);
    while (matcher.find()) {
      cleaned = matcher.replaceAll("$1$3");
      matcher = CJK_SPACES_PATTERN.matcher(cleaned);
    }

    cleaned = CJK_PUNCT_SPACES_1.matcher(cleaned).replaceAll("$1$3");
    cleaned = CJK_PUNCT_SPACES_2.matcher(cleaned).replaceAll("$1$3");

    cleaned = OCR_SPLIT_NUM_FIRST_PATTERN.matcher(cleaned).replaceAll("$1 $2");
    cleaned = OCR_SPLIT_TITLE_FIRST_PATTERN.matcher(cleaned).replaceAll("$2 $1");
    cleaned = BROKEN_NUM_DOT_PATTERN.matcher(cleaned).replaceAll("$1.$2");

    cleaned = BLOCK_TAG_START_PATTERN.matcher(cleaned).replaceAll("\n\n$1");
    cleaned = BLOCK_TAG_END_PATTERN.matcher(cleaned).replaceAll("$1\n\n");

    return cleaned.trim();
  }

  /** 将清洗后的 Markdown 解析为结构化逻辑段落片段并记录章节层级 */
  static List<LogicalSection> parseLogicalSections(String cleanedText) {
    String[] rawBlocks = cleanedText.split("\\n\\s*\\n");
    List<LogicalSection> sections = new ArrayList<>();
    String[] activeHeaders = new String[7];

    for (String block : rawBlocks) {
      String trimmed = block.trim();
      if (trimmed.isEmpty() || isTocParagraph(trimmed)) {
        continue;
      }

      String type = detectSectionType(trimmed);
      if (!"text".equals(type)) {
        String effectiveHeaderPath = resolveHeaderPath(activeHeaders);
        if ("table".equals(type)) {
          String leadInText = extractPrecedingLeadIn(sections);
          String tableContent =
              StrUtil.isNotBlank(leadInText) ? (leadInText + "\n\n" + trimmed) : trimmed;
          sections.add(new LogicalSection(tableContent, effectiveHeaderPath, "table", 0));
          continue;
        }
        sections.add(new LogicalSection(trimmed, effectiveHeaderPath, type, 0));
        continue;
      }

      List<String> lines = trimmed.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
      StringBuilder paragraphBuffer = new StringBuilder();
      int currentSectionHeadingLevel = 0;

      for (String line : lines) {
        HeadingInfo heading = detectHeadingOrInline(line);
        if (heading != null) {
          if (!paragraphBuffer.isEmpty()) {
            sections.add(
                new LogicalSection(
                    paragraphBuffer.toString().trim(),
                    resolveHeaderPath(activeHeaders),
                    "text",
                    currentSectionHeadingLevel));
            paragraphBuffer.setLength(0);
          }
          activeHeaders[heading.level()] = heading.title();
          for (int i = heading.level() + 1; i <= 6; i++) {
            activeHeaders[i] = null;
          }
          currentSectionHeadingLevel = heading.level();

          if (StrUtil.isNotBlank(heading.remainingContent())) {
            paragraphBuffer.append(heading.remainingContent()).append("\n");
          } else if (!line.startsWith("#")) {
            paragraphBuffer.append(line).append("\n");
          }
        } else {
          paragraphBuffer.append(line).append("\n");
        }
      }

      if (!paragraphBuffer.isEmpty()) {
        sections.add(
            new LogicalSection(
                paragraphBuffer.toString().trim(),
                resolveHeaderPath(activeHeaders),
                "text",
                currentSectionHeadingLevel));
      }
    }

    return sections;
  }

  @Nullable
  private static HeadingInfo detectHeadingOrInline(String line) {
    if (StrUtil.isBlank(line)) {
      return null;
    }
    String trimmed = line.trim();

    Matcher mdMatcher = MARKDOWN_HEADER_PATTERN.matcher(trimmed);
    if (mdMatcher.matches()) {
      int level = Math.min(6, mdMatcher.group(1).length());
      return new HeadingInfo(level, mdMatcher.group(2).trim(), null);
    }

    Matcher htmlMatcher = HTML_HEADER_PATTERN.matcher(trimmed);
    if (htmlMatcher.matches()) {
      int level = Math.min(6, Integer.parseInt(htmlMatcher.group(1)));
      return new HeadingInfo(level, htmlMatcher.group(2).trim(), null);
    }

    Matcher cjkChapterMatcher = CJK_CHAPTER_PATTERN.matcher(trimmed);
    if (cjkChapterMatcher.matches()) {
      return new HeadingInfo(1, trimmed, null);
    }

    Matcher cjkSubMatcher = CJK_SUBCHAPTER_PATTERN.matcher(trimmed);
    if (cjkSubMatcher.matches()) {
      return new HeadingInfo(2, trimmed, null);
    }

    return tryExtractNumberedHeading(trimmed);
  }

  @Nullable
  private static HeadingInfo tryExtractNumberedHeading(String trimmed) {
    if (PURE_NUMBERED_LINE_PATTERN.matcher(trimmed).matches()) {
      String cleanNum = trimmed.replaceAll("[、.]+$", "");
      int dotCount = StrUtil.count(cleanNum, '.');
      int level = Math.min(6, dotCount + 1);
      return new HeadingInfo(level, cleanNum, null);
    }

    Matcher numMatcher = NUMBERED_HEADER_PATTERN.matcher(trimmed);
    if (!numMatcher.matches()) {
      return null;
    }
    String numStr = numMatcher.group(1);
    int dots = StrUtil.count(numStr, '.');
    String rest = numMatcher.group(2).trim();

    if (DIGITS_ONLY_PATTERN.matcher(rest).matches()) {
      String fullNum = numStr + "." + rest;
      int dotCount = StrUtil.count(fullNum, '.');
      int level = Math.min(6, dotCount + 1);
      return new HeadingInfo(level, fullNum, null);
    }

    if (rest.length() <= 40 && !endsWithSentencePunctuation(rest)) {
      int level = Math.min(6, dots + 1);
      if (dots == 0) {
        return isValidLevel1Heading(rest) ? new HeadingInfo(1, numStr + ". " + rest, null) : null;
      }
      return new HeadingInfo(level, numStr + " " + rest, null);
    }

    int sepIdx = findFirstHeadingSeparator(rest);
    if (sepIdx > 0 && sepIdx <= 30) {
      String titlePart = rest.substring(0, sepIdx).trim();
      String bodyPart = rest.substring(sepIdx + 1).trim();
      if (bodyPart.startsWith("：") || bodyPart.startsWith(":") || bodyPart.startsWith("。")) {
        bodyPart = bodyPart.substring(1).trim();
      }
      int level = Math.min(6, dots + 1);
      if (dots == 0 && !isValidLevel1Heading(titlePart)) {
        return null;
      }
      String fullTitle = (dots == 0) ? (numStr + ". " + titlePart) : (numStr + " " + titlePart);
      return new HeadingInfo(level, fullTitle, bodyPart);
    }

    return null;
  }

  private static int findFirstHeadingSeparator(String text) {
    if (StrUtil.isBlank(text)) {
      return -1;
    }
    for (int i = 0; i < Math.min(text.length(), 30); i++) {
      char c = text.charAt(i);
      if (c == '：' || c == ':' || c == '。' || c == '；' || c == ';' || c == '\t') {
        return i;
      }
    }
    int dblSpace = text.indexOf("  ");
    if (dblSpace > 0 && dblSpace <= 30) {
      return dblSpace;
    }
    int space = text.indexOf(' ');
    if (space >= 2 && space <= 25) {
      String remaining = text.substring(space + 1).trim();
      if (endsWithSentencePunctuation(remaining)) {
        return space;
      }
    }
    return -1;
  }

  private static boolean isValidLevel1Heading(String title) {
    if (StrUtil.isBlank(title) || title.length() > 25) {
      return false;
    }
    if (title.contains("，")
        || title.contains(",")
        || title.contains("：")
        || title.contains(":")
        || title.contains("；")
        || title.contains(";")
        || title.contains("“")
        || title.contains("”")
        || title.contains("\"")) {
      return false;
    }
    for (String act : ACTION_PREFIXES) {
      if (title.startsWith(act) || (title.length() >= 4 && title.substring(0, 4).contains(act))) {
        return false;
      }
    }
    return true;
  }

  private static boolean endsWithSentencePunctuation(String text) {
    return text.endsWith("。")
        || text.endsWith("；")
        || text.endsWith(";")
        || text.endsWith("!")
        || text.endsWith("！")
        || text.endsWith("？")
        || text.endsWith("?");
  }

  private static String extractPrecedingLeadIn(List<LogicalSection> sections) {
    if (sections == null || sections.isEmpty()) {
      return "";
    }
    int maxLeadInChars = 300;
    List<String> absorbedTexts = new ArrayList<>();
    int accumulatedLength = 0;

    while (!sections.isEmpty()) {
      LogicalSection prev = sections.getLast();
      if (!"text".equals(prev.type()) || StrUtil.isBlank(prev.content())) {
        break;
      }
      String prevContent = prev.content().trim();
      if (accumulatedLength + prevContent.length() > maxLeadInChars) {
        break;
      }
      absorbedTexts.addFirst(prevContent);
      accumulatedLength += prevContent.length();
      sections.removeLast();
    }

    return String.join("\n\n", absorbedTexts).trim();
  }

  private static String detectSectionType(String text) {
    if (text.startsWith("<table")
        || (text.startsWith("|") && text.contains("|") && text.contains("\n"))) {
      return "table";
    }
    if (text.startsWith("<figure")) {
      return "figure";
    }
    if (text.startsWith("```") && text.endsWith("```")) {
      return "code";
    }
    return "text";
  }

  private static boolean isTocParagraph(String text) {
    if (StrUtil.isBlank(text)) {
      return false;
    }
    List<String> lines = text.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
    if (lines.isEmpty()) {
      return false;
    }
    long tocCount = lines.stream().filter(DeepDocOutlineParser::isTocLine).count();
    return tocCount == lines.size()
        || (lines.size() >= 2 && (double) tocCount / lines.size() >= 0.7);
  }

  private static boolean isTocLine(String text) {
    return text != null && TOC_LINE_PATTERN.matcher(text.trim()).matches();
  }

  private static String resolveHeaderPath(String[] activeHeaders) {
    List<String> list = new ArrayList<>();
    for (int i = 1; i <= 6; i++) {
      if (activeHeaders[i] != null && !activeHeaders[i].isBlank()) {
        list.add(activeHeaders[i]);
      }
    }
    return list.isEmpty() ? DEFAULT_FALLBACK_HEADER : String.join(" / ", list);
  }

  record HeadingInfo(int level, String title, @Nullable String remainingContent) {}

  record LogicalSection(String content, String headerPath, String type, int headingLevel) {}
}
