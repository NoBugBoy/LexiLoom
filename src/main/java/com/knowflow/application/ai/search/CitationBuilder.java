package com.knowflow.application.ai.search;

import cn.hutool.core.util.StrUtil;
import com.knowflow.application.ai.gateway.rerank.ScoredDocChunkVO;
import com.knowflow.application.ai.model.search.DocChunkDocument;
import com.knowflow.application.ai.model.vo.CitationVO;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 视觉坐标引用源构建器 (CitationBuilder)
 *
 * <p>核心职责：
 *
 * <ol>
 *   <li>将精排选出的优质切片映射为携带几何视觉坐标的引用对象 {@link CitationVO}，赋予自然编号 `[1]`, `[2]`；
 *   <li>100% 完整透传原件页码 {@code page}、物理矩形框坐标 {@code bbox} 及定位锚点 {@code anchor}，赋能前端 PDF 原件像素级高亮画框；
 *   <li>将引用源格式化为结构化参考上下文 (Context Snippets)，注入大模型提示词系统人设。
 * </ol>
 */
@Slf4j
@Component
public class CitationBuilder {

  private static final int DEFAULT_MAX_SNIPPET_LENGTH = 400;

  /**
   * 将经过精排排序后的切块候选列表构建为引用源列表 (1-indexed)
   *
   * @param scoredChunks 精排切块列表
   * @return 引用源不可变列表
   */
  public List<CitationVO> buildCitations(List<ScoredDocChunkVO> scoredChunks) {
    if (scoredChunks == null || scoredChunks.isEmpty()) {
      return List.of();
    }

    List<CitationVO> citations = new ArrayList<>(scoredChunks.size());

    for (int i = 0; i < scoredChunks.size(); i++) {
      ScoredDocChunkVO item = scoredChunks.get(i);
      DocChunkDocument chunk = item.chunk();
      if (chunk == null) {
        continue;
      }

      int citationIndex = i + 1;
      Long documentId = parseLongSafe(chunk.getDocumentId());
      String docTitle = StrUtil.blankToDefault(chunk.getTitle(), "未知文档").trim();
      String headerPath = StrUtil.blankToDefault(chunk.getHeaderPath(), "正文").trim();
      String snippet = truncateSnippet(chunk.getContent(), DEFAULT_MAX_SNIPPET_LENGTH);
      Double score = item.score();
      Integer page = chunk.getPage();
      List<Float> bbox = chunk.getBbox() != null ? chunk.getBbox() : List.of();
      String chunkType = StrUtil.blankToDefault(chunk.getChunkType(), "text").trim().toLowerCase();
      String anchor = chunk.getAnchor();

      citations.add(
          new CitationVO(
              citationIndex,
              documentId,
              docTitle,
              headerPath,
              snippet,
              score,
              page,
              bbox,
              chunkType,
              anchor));
    }

    log.info("【CitationBuilder】成功构建 {} 个视觉溯源引用对象", citations.size());
    return List.copyOf(citations);
  }

  /**
   * 将引用源列表格式化为注入大模型 Prompt 的参考上下文 Markdown 文本
   *
   * @param citations 引用源列表
   * @return 格式化后的参考上下文内容
   */
  public String formatPromptContext(List<CitationVO> citations) {
    if (citations == null || citations.isEmpty()) {
      return "> 无相关知识库参考资料。\n";
    }

    StringBuilder sb = new StringBuilder();
    for (CitationVO cite : citations) {
      sb.append(
              String.format(
                  "参考片段 [%d] (来源：《%s》 > 章节: %s):\n",
                  cite.citationIndex(), cite.docTitle(), cite.headerPath()))
          .append(cite.snippet())
          .append("\n\n");
    }
    return sb.toString().trim();
  }

  private Long parseLongSafe(String str) {
    if (StrUtil.isBlank(str)) {
      return null;
    }
    try {
      return Long.parseLong(str.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private String truncateSnippet(String content, int maxLength) {
    if (StrUtil.isBlank(content)) {
      return "";
    }
    String trimmed = content.trim();
    if (trimmed.length() <= maxLength) {
      return trimmed;
    }
    return trimmed.substring(0, maxLength) + "...";
  }
}
