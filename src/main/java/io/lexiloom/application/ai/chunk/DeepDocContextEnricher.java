package io.lexiloom.application.ai.chunk;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.chunk.DeepDocOutlineParser.LogicalSection;
import io.lexiloom.application.ai.chunk.DeepDocSlidingPacker.RawChunkItem;
import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.document.api.dto.DocSourceFileDTO;
import io.lexiloom.application.document.api.dto.DocumentDTO;
import io.lexiloom.application.document.api.dto.DocTextChunk;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * 文档语义分块、滑动堆叠与上下文帽子富化器 (DeepDocContextEnricher)
 *
 * <p>核心架构与现代 RAG 最佳实践：
 *
 * <ol>
 *   <li><b>完整内容驱动</b>：基于文档解析器输出的完整 Markdown 内容进行全篇处理，摆脱底层排版碎块依赖；
 *   <li><b>数据清洗与防腐</b>：委派 {@link DeepDocOutlineParser} 剥离垃圾内嵌图片、控制字符与异常汉字空格，识别多级大纲；
 *   <li><b>语义边界感知分块与重叠堆叠</b>：委派 {@link DeepDocSlidingPacker} 执行章节聚合、按句滑动窗口切分与超长 HTML 表格保表头切块；
 *   <li><b>上下文帽子动态加冠 (Context Prefix Enrichment)</b>：为每个切片注入全局文档标题与章节面包屑前缀，彻底解决跨段检索语义失真问题。
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeepDocContextEnricher {

  private static final int DEFAULT_TARGET_CHUNK_TOKENS = 512;
  private static final int DEFAULT_OVERLAP_TOKENS = 64;
  private static final int DEFAULT_MAX_TOKEN_LIMIT = 2000;

  private final AiProperties aiProperties;

  /**
   * 核心富化方法：基于主文档 DTO 与来源文件 DTO 编排清洗、Token 分块与堆叠
   *
   * @param doc 主文档传输对象
   * @param sourceFile 来源文件传输对象 (可为空)
   * @return 经过滑动切块与帽子富化后的切片列表
   */
  public List<EnrichedDocChunk> enrich(DocumentDTO doc, @Nullable DocSourceFileDTO sourceFile) {
    if (doc == null) {
      return List.of();
    }
    String title = StrUtil.blankToDefault(doc.title(), "无标题文档").trim();
    String content = doc.content();

    if ((StrUtil.isBlank(content) || isPreviewPlaceholder(content))
        && sourceFile != null
        && StrUtil.isNotBlank(sourceFile.rawText())) {
      content = sourceFile.rawText();
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
    String cleanedText = DeepDocOutlineParser.cleanMarkdownText(markdownContent);
    if (cleanedText.isBlank()) {
      return List.of();
    }

    int targetTokens = getTargetChunkTokens();
    int overlapTokens = getOverlapTokens();
    int maxLimit = getMaxTokenLimit();
    boolean prefixEnabled = isContextPrefixEnabled();

    // 1. 将全文拆分为逻辑段落块并维护大纲上下文
    List<LogicalSection> sections = DeepDocOutlineParser.parseLogicalSections(cleanedText);
    if (sections.isEmpty()) {
      return List.of();
    }

    // 2. Token 维度分块与滑动重叠堆叠
    List<RawChunkItem> rawChunks =
        DeepDocSlidingPacker.chunkAndOverlap(sections, targetTokens, overlapTokens, maxLimit);
    if (rawChunks.isEmpty()) {
      return List.of();
    }

    // 3. 上下文帽子动态加冠 (Context Prefix Enrichment)
    List<EnrichedDocChunk> results = new ArrayList<>(rawChunks.size());
    for (int i = 0; i < rawChunks.size(); i++) {
      RawChunkItem item = rawChunks.get(i);
      String chunkContent = item.content();
      String headerPath =
          StrUtil.blankToDefault(item.headerPath(), DeepDocOutlineParser.DEFAULT_FALLBACK_HEADER);
      String chunkType = StrUtil.blankToDefault(item.chunkType(), "text");

      String contextPrefix = "";
      String textForEmbedding = DeepDocSlidingPacker.stripHtmlForEmbedding(chunkContent);
      String enrichedText;
      if (prefixEnabled) {
        contextPrefix =
            String.format("[文档: 《%s》] > [章节: %s] > [类型: %s]", cleanTitle, headerPath, chunkType);
        enrichedText = contextPrefix + "\n\n" + textForEmbedding;
      } else {
        enrichedText = textForEmbedding;
      }

      results.add(
          EnrichedDocChunk.of(
              i,
              "chunk_" + i,
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

  /** 兼容保留旧版重载签名：基于原始 DocTextChunk 列表富化 (用于历史单测和向后兼容) */
  public List<EnrichedDocChunk> enrich(String title, List<DocTextChunk> rawChunks) {
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

  /** 轻量估算中英混合文本的 Token 数量 (中文 1.2 token/字，英文 0.3 token/字符) */
  public static int estimateTokens(String text) {
    return DeepDocSlidingPacker.estimateTokens(text);
  }

  private boolean isPreviewPlaceholder(@Nullable String content) {
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
}
