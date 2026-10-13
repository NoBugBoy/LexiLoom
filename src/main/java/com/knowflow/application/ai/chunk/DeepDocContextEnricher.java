package com.knowflow.application.ai.chunk;

import cn.hutool.core.util.StrUtil;
import com.knowflow.application.ai.config.AiProperties;
import com.knowflow.application.document.api.dto.DocSourceFileDTO;
import com.knowflow.application.document.api.dto.DocumentDTO;
import com.knowflow.application.document.parser.DocumentContentCleaner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 文档语义分块、滑动堆叠与上下文帽子富化器 (DeepDocContextEnricher)
 *
 * <p>核心架构与现代 RAG 最佳实践：
 *
 * <ol>
 *   <li><b>完整内容驱动</b>：基于文档解析器输出的完整 Markdown 内容进行全篇处理，摆脱底层排版碎块依赖；
 *   <li><b>数据清洗与防腐</b>：剥离垃圾内嵌图片、控制字符与异常汉字空格，规范段落换行；
 *   <li><b>Markdown 结构感知分块 (Structure-Aware)</b>：维护大纲标题层级栈，避免孤立短标题，完整保护 Markdown 表格与代码块；
 *   <li><b>Token 维度优雅滑动切分与堆叠 (Sliding Window & Overlap)</b>：对长段落按标点断句滑动切分，保留重叠堆叠上下文，确保切片间语义平滑过渡；
 *   <li><b>上下文帽子动态加冠 (Context Prefix Enrichment)</b>：为每个切片注入全局文档标题与章节面包屑前缀，彻底解决跨段检索语义失真问题。
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeepDocContextEnricher {

  private static final Pattern MARKDOWN_HEADER_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");
  private static final Pattern HTML_HEADER_PATTERN =
      Pattern.compile("^<h([1-6])\\b[^>]*>(.*?)</h\\1>$", Pattern.CASE_INSENSITIVE);
  // 匹配多级数字点编号标题 (如 3. 总体方案设计, 3.1 总体结构, 3.2.1 模块划分, 2.3 条件与限制)
  private static final Pattern NUMBERED_HEADER_PATTERN =
      Pattern.compile("^(\\d+(?:\\.\\d+){0,4})[、.\\s]+([^。！？；\\n]+)$");
  // 匹配中文章节大纲编号 (如 第一章 总体方案设计, 一、总体结构)
  private static final Pattern CJK_CHAPTER_PATTERN =
      Pattern.compile("^(?:第[一二三四五六七八九十0-9]+[章节篇部分条]|[一二三四五六七八九十]+[、.])[\\s]*([^。！？；\\n]+)$");
  // 匹配中文小节编号 (如 （一）模块划分, (1) 模块划分)
  private static final Pattern CJK_SUBCHAPTER_PATTERN =
      Pattern.compile("^(?:（[一二三四五六七八九十0-9]+）|\\([一二三四五六七八九十0-9]+\\))[\\s]*([^。！？；\\n]+)$");

  private static final Pattern TOC_LINE_PATTERN =
      Pattern.compile(
          "^\\s*(?:(?:第[一二三四五六七八九十百0-9]+[章篇部卷]|\\d+(?:\\.\\d+){0,5}[、.]?)\\s*)?\\S.+?(?:[\\.·…]{2,}\\s*\\d+|\\s*\\d{1,3})\\s*$");
  // 断句正则：排除数字点编号中的英文句号 (如 3.1、3.2.1、v1.4)，仅在非数字结尾处断句
  private static final Pattern SENTENCE_SPLIT_PATTERN =
      Pattern.compile("(?<=[。！？；!?;]|\\.(?!\\d))\\s*");
  private static final Pattern CJK_SPACES_PATTERN =
      Pattern.compile("([\\u4e00-\\u9fa5])([ \\t　]+)([\\u4e00-\\u9fa5])");
  private static final Pattern CJK_PUNCT_SPACES_1 =
      Pattern.compile("([\\u4e00-\\u9fa5])([ \\t　]+)([，。！？；：、“”‘’（）《》【】])");
  private static final Pattern CJK_PUNCT_SPACES_2 =
      Pattern.compile("([，。！？；：、“”‘’（）《》【】])([ \\t　]+)([\\u4e00-\\u9fa5])");

  private static final int DEFAULT_TARGET_CHUNK_TOKENS = 512;
  private static final int DEFAULT_OVERLAP_TOKENS = 64;
  private static final int DEFAULT_MAX_TOKEN_LIMIT = 2000;
  private static final String DEFAULT_FALLBACK_HEADER = "正文";

  private final AiProperties aiProperties;

  /**
   * 核心富化方法：基于主文档 DTO 与来源文件 DTO 编排清洗、Token 分块与堆叠
   *
   * @param doc 主文档传输对象
   * @param sourceFile 来源文件传输对象 (可为空)
   * @return 经过滑动切块与帽子富化后的切片列表
   */
  public List<EnrichedDocChunk> enrich(DocumentDTO doc, DocSourceFileDTO sourceFile) {
    if (doc == null) {
      return List.of();
    }
    String title = StrUtil.blankToDefault(doc.title(), "无标题文档").trim();
    String content = doc.content();

    // 若主文档内容为空或为预览占位符，尝试从来源文件取文本兜底
    if (StrUtil.isBlank(content) || isPreviewPlaceholder(content)) {
      if (sourceFile != null && StrUtil.isNotBlank(sourceFile.rawText())) {
        content = sourceFile.rawText();
      }
    }

    if (StrUtil.isBlank(content)) {
      log.warn("【DeepDocContextEnricher】文档内容为空，跳过切块: docId={}, title={}", doc.id(), title);
      return List.of();
    }

    return enrichFromMarkdown(title, content);
  }

  /**
   * 基于文档标题与完整 Markdown 文本执行数据清洗、Token 维度递归分块与滑动堆叠
   *
   * @param title 文档标题
   * @param markdownContent 完整 Markdown 文本
   * @return 富化后的切块列表
   */
  public List<EnrichedDocChunk> enrichFromMarkdown(String title, String markdownContent) {
    if (StrUtil.isBlank(markdownContent)) {
      return List.of();
    }

    String cleanTitle = StrUtil.blankToDefault(title, "无标题文档").trim();
    String cleanedText = cleanMarkdownText(markdownContent);

    if (cleanedText.isBlank()) {
      return List.of();
    }

    int targetTokens = getTargetChunkTokens();
    int overlapTokens = getOverlapTokens();
    int maxLimit = getMaxTokenLimit();
    boolean prefixEnabled = isContextPrefixEnabled();

    // 1. 将全文拆分为逻辑段落块并维护大纲上下文
    List<LogicalSection> sections = parseLogicalSections(cleanedText);
    if (sections.isEmpty()) {
      return List.of();
    }

    // 2. Token 维度分块与滑动重叠堆叠
    List<RawChunkItem> rawChunks = chunkAndOverlap(sections, targetTokens, overlapTokens, maxLimit);
    if (rawChunks.isEmpty()) {
      return List.of();
    }

    // 3. 上下文帽子动态加冠 (Context Prefix Enrichment)
    List<EnrichedDocChunk> results = new ArrayList<>(rawChunks.size());
    for (int i = 0; i < rawChunks.size(); i++) {
      RawChunkItem item = rawChunks.get(i);
      String chunkContent = item.content();
      String headerPath = StrUtil.blankToDefault(item.headerPath(), DEFAULT_FALLBACK_HEADER);
      String chunkType = StrUtil.blankToDefault(item.chunkType(), "text");

      String contextPrefix = "";
      String enrichedText;
      // 向量计算双轨制：对 HTML 标签做轻量文本投影，避免 HTML 标记稀释向量语义
      String textForEmbedding = stripHtmlForEmbedding(chunkContent);
      if (prefixEnabled) {
        contextPrefix =
            String.format("[文档: 《%s》] > [章节: %s] > [类型: %s]", cleanTitle, headerPath, chunkType);
        enrichedText = contextPrefix + "\n\n" + textForEmbedding;
      } else {
        enrichedText = textForEmbedding;
      }

      String chunkId = "chunk_" + i;
      results.add(
          EnrichedDocChunk.of(
              i,
              chunkId,
              cleanTitle,
              headerPath,
              chunkContent,
              contextPrefix,
              enrichedText,
              chunkType,
              item.page(),
              null,
              item.anchor()));
    }

    log.info(
        "【DeepDocContextEnricher】文档 Token 清洗与滑动切分完成: title=[{}], 切块总数={}, 目标Token={}, 重叠Token={}",
        cleanTitle,
        results.size(),
        targetTokens,
        overlapTokens);
    return List.copyOf(results);
  }

  /**
   * 兼容保留旧版重载签名：基于原始 DocTextChunk 列表富化 (用于历史单测和向后兼容)
   */
  public List<EnrichedDocChunk> enrich(String title, List<com.knowflow.application.document.parser.DocTextChunk> rawChunks) {
    if (rawChunks == null || rawChunks.isEmpty()) {
      return List.of();
    }
    StringBuilder sb = new StringBuilder();
    for (var chunk : rawChunks) {
      if (chunk != null && StrUtil.isNotBlank(chunk.content())) {
        sb.append(chunk.content().trim()).append("\n\n");
      }
    }
    return enrichFromMarkdown(title, sb.toString());
  }

  /** 数据清洗：去除脏内嵌图片、多余空行与汉字间异常空格 */
  private String cleanMarkdownText(String markdown) {
    if (StrUtil.isBlank(markdown)) {
      return "";
    }
    // 清洗无效内嵌 Base64 图片
    String cleaned = DocumentContentCleaner.cleanEmbeddedImages(markdown);
    // 清理 DeepDoc 历史坐标残余
    cleaned = DocumentContentCleaner.cleanCoordinates(cleaned);

    // 防御性消除汉字间连续伪横向空格
    Matcher matcher = CJK_SPACES_PATTERN.matcher(cleaned);
    while (matcher.find()) {
      cleaned = matcher.replaceAll("$1$3");
      matcher = CJK_SPACES_PATTERN.matcher(cleaned);
    }

    // 消除汉字与中文标点符号间的伪横向空格
    cleaned = CJK_PUNCT_SPACES_1.matcher(cleaned).replaceAll("$1$3");
    cleaned = CJK_PUNCT_SPACES_2.matcher(cleaned).replaceAll("$1$3");

    return cleaned.trim();
  }

  /** 识别文本行是否为标题，返回对应的大纲层级 (1~6) 与标题文本 */
  private HeadingInfo detectHeading(String line) {
    if (StrUtil.isBlank(line)) {
      return null;
    }
    String trimmed = line.trim();
    // 标题行通常较短，且绝不以句末标点结尾
    if (trimmed.length() > 60
        || trimmed.endsWith("。")
        || trimmed.endsWith("；")
        || trimmed.endsWith(";")
        || trimmed.endsWith("!")
        || trimmed.endsWith("？")) {
      return null;
    }

    // 1. Markdown 标题: # 总体方案设计
    Matcher mdMatcher = MARKDOWN_HEADER_PATTERN.matcher(trimmed);
    if (mdMatcher.matches()) {
      int level = Math.min(6, mdMatcher.group(1).length());
      return new HeadingInfo(level, mdMatcher.group(2).trim());
    }

    // 2. HTML 标题: <h1>总体方案设计</h1>
    Matcher htmlMatcher = HTML_HEADER_PATTERN.matcher(trimmed);
    if (htmlMatcher.matches()) {
      int level = Math.min(6, Integer.parseInt(htmlMatcher.group(1)));
      return new HeadingInfo(level, htmlMatcher.group(2).trim());
    }

    // 3. 数字编号多级标题: 3. 总体方案设计, 3.1 系统总体结构, 3.2.1 模块划分, 2.3 条件与限制
    Matcher numMatcher = NUMBERED_HEADER_PATTERN.matcher(trimmed);
    if (numMatcher.matches()) {
      String numStr = numMatcher.group(1);
      int dots = 0;
      for (int i = 0; i < numStr.length(); i++) {
        if (numStr.charAt(i) == '.') {
          dots++;
        }
      }
      int level = Math.min(6, dots + 1);
      return new HeadingInfo(level, trimmed);
    }

    // 4. 中文章节大纲编号: 第一章 总体方案设计, 一、总体结构
    Matcher cjkChapterMatcher = CJK_CHAPTER_PATTERN.matcher(trimmed);
    if (cjkChapterMatcher.matches()) {
      return new HeadingInfo(1, trimmed);
    }

    // 5. 中文小节编号: （一）模块划分, (1) 模块划分
    Matcher cjkSubMatcher = CJK_SUBCHAPTER_PATTERN.matcher(trimmed);
    if (cjkSubMatcher.matches()) {
      return new HeadingInfo(2, trimmed);
    }

    return null;
  }

  /** 将 Markdown 解析为结构化的逻辑段落片段并记录章节层级 */
  private List<LogicalSection> parseLogicalSections(String cleanedText) {
    String[] rawBlocks = cleanedText.split("\\n\\s*\\n");
    List<LogicalSection> sections = new ArrayList<>();
    String[] activeHeaders = new String[7]; // 记录 1~6 级当前有效标题

    for (String block : rawBlocks) {
      String trimmed = block.trim();
      if (trimmed.isEmpty()) {
        continue;
      }

      // 过滤连续目录行 (TOC)，目录行缺乏完整检索正文价值
      if (isTocParagraph(trimmed)) {
        continue;
      }

      // 特殊结构块（表格、插图卡片、代码块）
      String type = detectSectionType(trimmed);
      if (!"text".equals(type)) {
        sections.add(new LogicalSection(trimmed, resolveHeaderPath(activeHeaders), type, 0));
        continue;
      }

      // 普通文本段落：逐行探测大纲标题并维护多级面包屑路径
      List<String> lines = trimmed.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
      StringBuilder paragraphBuffer = new StringBuilder();
      int currentSectionHeadingLevel = 0;

      for (String line : lines) {
        HeadingInfo heading = detectHeading(line);
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
          // 若为纯 Markdown 标题 (# 标题)，只作为章节大纲栈更新，不强行注入正文正文
          if (line.startsWith("#")) {
            continue;
          }
          paragraphBuffer.append(line).append("\n");
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

  /**
   * 语义边界感知贪心装箱分块与真正滑动重叠堆叠 (Hierarchical Semantic Packing with True Overlap)
   *
   * <p>规则：
   * 1. 遇到重大独立章节切换 (Level 1 或 Level 2 标题)，若当前已有实质内容，封包当前 Chunk，保持章节独立性；
   * 2. 三级及以下子小节 (如 3.2.1, 3.2.2) 贪心聚合在同一个 Chunk 中，保持上下文连贯；
   * 3. 若放入下一小节会导致该小节被腰斩截断，提前封包当前 Chunk，将下一小节完整放到新 Chunk 开头；
   * 4. 封包后提取上一 Chunk 末尾完整句子作为 Overlap 注入新 Chunk 开头，确保切片首尾呼应；
   * 5. 若单段本身极端超长，按标点符号断句并进行滑动重叠切分。
   */
  private List<RawChunkItem> chunkAndOverlap(
      List<LogicalSection> sections, int targetTokens, int overlapTokens, int maxLimit) {
    List<RawChunkItem> items = new ArrayList<>();
    StringBuilder currentBuffer = new StringBuilder();
    String currentHeaderPath = null;
    String lastOverlapTail = "";

    for (LogicalSection sec : sections) {
      // 1. 特殊结构块（表格、插图卡片、代码块）：独立成块保护
      if ("table".equals(sec.type()) || "figure".equals(sec.type()) || "code".equals(sec.type())) {
        if (!currentBuffer.isEmpty()) {
          String flushedContent = truncateToLimit(currentBuffer.toString().trim(), maxLimit);
          items.add(new RawChunkItem(flushedContent, currentHeaderPath, "text", null, null));
          lastOverlapTail = extractTailOverlap(flushedContent, overlapTokens);
          currentBuffer.setLength(0);
        }
        if ("table".equals(sec.type())
            && sec.content().startsWith("<table")
            && estimateTokens(sec.content()) > targetTokens) {
          chunkLargeHtmlTable(items, sec.content(), sec.headerPath(), targetTokens, maxLimit);
        } else {
          items.add(new RawChunkItem(truncateToLimit(sec.content(), maxLimit), sec.headerPath(), sec.type(), null, null));
        }
        lastOverlapTail = "";
        continue;
      }

      int secTokens = estimateTokens(sec.content());

      // 2. 单个段落自身极端超长：按标点符号断句后滑动重叠切分
      if (secTokens > targetTokens) {
        if (!currentBuffer.isEmpty()) {
          String flushedContent = truncateToLimit(currentBuffer.toString().trim(), maxLimit);
          items.add(new RawChunkItem(flushedContent, currentHeaderPath, "text", null, null));
          currentBuffer.setLength(0);
        }
        List<RawChunkItem> subChunks =
            splitLongSectionWithOverlap(sec.content(), sec.headerPath(), targetTokens, overlapTokens, maxLimit);
        if (!subChunks.isEmpty()) {
          items.addAll(subChunks);
          lastOverlapTail = extractTailOverlap(subChunks.getLast().content(), overlapTokens);
        }
        continue;
      }

      // 3. 贪心装箱判定：
      // (1) 若遇到非兼容的独立大纲分支切换 (如从 3.1 走到 3.2，或从 存储设计 走到 缓存设计)，且当前已有实质内容，封包上一章节保持章节独立；
      boolean isBranchSwitch =
          currentHeaderPath != null
              && !isCompatibleSection(currentHeaderPath, sec.headerPath())
              && !currentBuffer.isEmpty()
              && estimateTokens(currentBuffer.toString()) >= 15;

      // (2) 检查加入当前小节后是否超出目标 Token，若超出则提前封包避免当前小节腰斩
      int currentTokens = estimateTokens(currentBuffer.toString());
      boolean isOverflow = (currentTokens + secTokens > targetTokens);

      if ((isBranchSwitch || isOverflow) && !currentBuffer.isEmpty()) {
        String flushedContent = truncateToLimit(currentBuffer.toString().trim(), maxLimit);
        items.add(new RawChunkItem(flushedContent, currentHeaderPath, "text", null, null));
        // 关键机制：若是由于 Token 溢出导致的正文切分，提取尾部完整句子作为 Overlap 衔接下文；
        // 若为完全独立的章节分支切换 (isBranchSwitch)，保持章节主题纯净，不跨章节污染。
        if (isOverflow) {
          lastOverlapTail = extractTailOverlap(flushedContent, overlapTokens);
        } else {
          lastOverlapTail = "";
        }
        currentBuffer.setLength(0);
      }

      // 4. 将当前小节装入缓冲（若缓冲刚刚清空且有 Overlap，先注入 Overlap 衔接前文）
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

  /**
   * 判断两个章节路径是否属于可以聚合的同门模块
   * 1. 若 pathB 是 pathA 的子章节 (如 3.2 的子章节 3.2.1)，允许聚合；
   * 2. 若 pathA 是 pathB 的父章节，允许聚合；
   * 3. 若两者具有相同的二级大纲前缀 (如 3.2.1 与 3.2.2 均属于 3.2)，允许在 Token 允许范围内聚合；
   * 4. 否则 (如从 3.1 切换到 3.2，或从 存储设计 切换到 缓存设计)，属于不同独立分支，应封包。
   */
  private boolean isCompatibleSection(String currentPath, String nextPath) {
    if (currentPath == null || nextPath == null || currentPath.equals(nextPath)) {
      return true;
    }
    // 包含父子前缀关系
    if (nextPath.startsWith(currentPath) || currentPath.startsWith(nextPath)) {
      return true;
    }
    // 获取二级大纲主干 (如 "总体方案 / 3.2 模块设计")
    String currentParent = extractMajorBranch(currentPath);
    String nextParent = extractMajorBranch(nextPath);
    return currentParent.equals(nextParent);
  }

  private String extractMajorBranch(String path) {
    if (path == null) {
      return "";
    }
    String[] parts = path.split(" / ");
    if (parts.length <= 2) {
      return path;
    }
    return parts[0] + " / " + parts[1];
  }

  /** 从文本尾部提取约 overlapTokens 的完整句子，作为下一切块的平滑重叠上下文 */
  private String extractTailOverlap(String text, int overlapTokens) {
    if (StrUtil.isBlank(text) || overlapTokens <= 0) {
      return "";
    }
    String trimmed = text.trim();
    String[] sentences = SENTENCE_SPLIT_PATTERN.split(trimmed);
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
      // 只有一句话时直接取该句
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

  /** 超长文本按标点断句并按滑动窗口带 Overlap 切分 */
  private List<RawChunkItem> splitLongSectionWithOverlap(
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
      return List.of(new RawChunkItem(truncateToLimit(text, maxLimit), headerPath, "text", null, null));
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

  /** 轻量估算中英混合文本的 Token 数量 (中文 1.2 token/字，英文 0.3 token/字符) */
  public static int estimateTokens(String text) {
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

  private String truncateToLimit(String text, int maxLimit) {
    if (text.length() > maxLimit) {
      log.warn("【DeepDocContextEnricher】原子切块字符数超限 ({} > {})，执行安全截断保护", text.length(), maxLimit);
      return text.substring(0, maxLimit);
    }
    return text;
  }

  private String detectSectionType(String text) {
    if (text.startsWith("<table") || (text.startsWith("|") && text.contains("|") && text.contains("\n"))) {
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

  /** 超长 HTML 表格按 <tr> 分行切分，并为每个切片自动复制第一行表头 (Header-Preserved Chunking) */
  private void chunkLargeHtmlTable(
      List<RawChunkItem> items, String tableHtml, String headerPath, int targetTokens, int maxLimit) {
    Matcher rowMatcher = Pattern.compile("<tr\\b[^>]*>[\\s\\S]*?</tr>", Pattern.CASE_INSENSITIVE).matcher(tableHtml);
    List<String> rows = new ArrayList<>();
    while (rowMatcher.find()) {
      rows.add(rowMatcher.group(0));
    }

    if (rows.size() <= 2) {
      items.add(new RawChunkItem(truncateToLimit(tableHtml, maxLimit), headerPath, "table", null, null));
      return;
    }

    String headerRow = rows.getFirst();
    StringBuilder currentBatch = new StringBuilder();
    int currentTokens = estimateTokens(headerRow);

    for (int i = 1; i < rows.size(); i++) {
      String row = rows.get(i);
      int rowTokens = estimateTokens(row);
      if (currentTokens + rowTokens > targetTokens && !currentBatch.isEmpty()) {
        String subTable = "<table>\n" + headerRow + "\n" + currentBatch + "</table>";
        items.add(new RawChunkItem(truncateToLimit(subTable, maxLimit), headerPath, "table", null, null));
        currentBatch.setLength(0);
        currentTokens = estimateTokens(headerRow);
      }
      currentBatch.append(row).append("\n");
      currentTokens += rowTokens;
    }

    if (!currentBatch.isEmpty()) {
      String subTable = "<table>\n" + headerRow + "\n" + currentBatch + "</table>";
      items.add(new RawChunkItem(truncateToLimit(subTable, maxLimit), headerPath, "table", null, null));
    }
  }

  private String stripHtmlForEmbedding(String content) {
    if (StrUtil.isBlank(content)) {
      return "";
    }
    if (content.contains("<") && content.contains(">")) {
      return content.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
    return content;
  }

  private boolean isTocParagraph(String text) {
    if (StrUtil.isBlank(text)) {
      return false;
    }
    List<String> lines = text.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
    if (lines.isEmpty()) {
      return false;
    }
    long tocCount = lines.stream().filter(this::isTocLine).count();
    return tocCount == lines.size() || (lines.size() >= 2 && (double) tocCount / lines.size() >= 0.7);
  }

  private boolean isTocLine(String text) {
    return text != null && TOC_LINE_PATTERN.matcher(text.trim()).matches();
  }

  private String resolveHeaderPath(String[] activeHeaders) {
    List<String> list = new ArrayList<>();
    for (int i = 1; i <= 6; i++) {
      if (activeHeaders[i] != null && !activeHeaders[i].isBlank()) {
        list.add(activeHeaders[i]);
      }
    }
    return list.isEmpty() ? DEFAULT_FALLBACK_HEADER : String.join(" / ", list);
  }

  private boolean isPreviewPlaceholder(String content) {
    return content != null && content.contains("[PDF 固化版面原件预览模式]");
  }

  private int getTargetChunkTokens() {
    return (aiProperties != null && aiProperties.getChunk() != null)
        ? aiProperties.getChunk().getTargetChunkTokens()
        : DEFAULT_TARGET_CHUNK_TOKENS;
  }

  private int getOverlapTokens() {
    return (aiProperties != null && aiProperties.getChunk() != null)
        ? aiProperties.getChunk().getOverlapTokens()
        : DEFAULT_OVERLAP_TOKENS;
  }

  private int getMaxTokenLimit() {
    return (aiProperties != null && aiProperties.getChunk() != null)
        ? aiProperties.getChunk().getMaxTokenLimit()
        : DEFAULT_MAX_TOKEN_LIMIT;
  }

  private boolean isContextPrefixEnabled() {
    return aiProperties == null
        || aiProperties.getChunk() == null
        || aiProperties.getChunk().isContextPrefixEnabled();
  }

  /** 内部标题探测信息值对象 */
  private record HeadingInfo(int level, String title) {}

  /** 内部逻辑段落值对象 */
  private record LogicalSection(
      String content, String headerPath, String type, int headingLevel) {}

  /** 内部中间切块数据项 */
  private record RawChunkItem(
      String content, String headerPath, String chunkType, Integer page, String anchor) {}
}
