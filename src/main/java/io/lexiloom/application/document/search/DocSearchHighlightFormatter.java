package io.lexiloom.application.document.search;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.document.search.dto.DocSearchItemVO;
import java.util.List;
import java.util.Map;
import org.springframework.data.elasticsearch.core.SearchHit;

/**
 * 检索结果高亮片段提取与 DTO 组装器
 */
public final class DocSearchHighlightFormatter {

  private static final int MAX_SNIPPET_LENGTH = DocSearchQueryBuilder.MAX_SNIPPET_LENGTH;

  private DocSearchHighlightFormatter() {}

  /**
   * 将 SearchHit 转换为 DocSearchItemVO，提取高亮或兜底正文，并填充工作空间元数据
   */
  public static DocSearchItemVO mapToItemVO(
      SearchHit<DocSearchDocument> hit,
      Map<Long, String> spaceNames,
      Map<Long, String> relationTypes) {
    DocSearchDocument doc = hit.getContent();
    Map<String, List<String>> hlMap = hit.getHighlightFields();

    String title = extractFirstHighlight(hlMap, "title", doc.getTitle());
    String summary = extractFirstHighlight(hlMap, "summary", doc.getSummary());
    String hitSnippet = extractHitSnippet(hlMap, doc);

    Long wsId = null;
    if (doc.getWorkspaceId() != null) {
      try {
        wsId = Long.parseLong(doc.getWorkspaceId());
      } catch (NumberFormatException ignored) {
      }
    }

    String wsName = wsId != null ? spaceNames.getOrDefault(wsId, "工作空间#" + wsId) : null;
    String relationType = wsId != null ? relationTypes.getOrDefault(wsId, "PRIMARY") : "PRIMARY";

    return new DocSearchItemVO(
        doc.getId(),
        title,
        summary,
        hitSnippet,
        doc.getCategoryId(),
        doc.getTags(),
        doc.getSourceType(),
        doc.getPublishedAt(),
        wsId,
        wsName,
        relationType);
  }

  /**
   * 提取搜索命中断片
   */
  public static String extractHitSnippet(Map<String, List<String>> hlMap, DocSearchDocument doc) {
    List<String> rawTextHl = hlMap.get("rawText");
    if (rawTextHl != null && !rawTextHl.isEmpty()) {
      return safeTruncateHtml(String.join(" ... ", rawTextHl), MAX_SNIPPET_LENGTH);
    }

    List<String> contentHl = hlMap.get("content");
    if (contentHl != null && !contentHl.isEmpty()) {
      return safeTruncateHtml(String.join(" ... ", contentHl), MAX_SNIPPET_LENGTH);
    }

    if (StrUtil.isNotBlank(doc.getSummary())) {
      return safeTruncateHtml(doc.getSummary(), MAX_SNIPPET_LENGTH);
    }

    if (StrUtil.isNotBlank(doc.getRawText())) {
      return safeTruncateHtml(doc.getRawText(), MAX_SNIPPET_LENGTH);
    }

    if (StrUtil.isNotBlank(doc.getContent())) {
      return safeTruncateHtml(doc.getContent(), MAX_SNIPPET_LENGTH);
    }

    return "";
  }

  public static String extractFirstHighlight(
      Map<String, List<String>> hlMap, String field, String defaultValue) {
    List<String> fragments = hlMap.get(field);
    if (fragments != null && !fragments.isEmpty()) {
      return String.join("...", fragments);
    }
    return defaultValue != null ? defaultValue : "";
  }

  /**
   * 安全截断带高亮 HTML 标签的文本，自动补齐未闭合的 </em> 标签
   */
  public static String safeTruncateHtml(String text, int maxLength) {
    if (text == null) {
      return "";
    }
    if (text.length() <= maxLength) {
      return text;
    }

    String truncated = text.substring(0, maxLength);

    int lastOpenBracket = truncated.lastIndexOf('<');
    int lastCloseBracket = truncated.lastIndexOf('>');
    if (lastOpenBracket > lastCloseBracket) {
      truncated = truncated.substring(0, lastOpenBracket);
    }

    int openCount = StrUtil.count(truncated, "<em class=\"hl\">");
    int closeCount = StrUtil.count(truncated, "</em>");

    StringBuilder sb = new StringBuilder(truncated).append("...");
    for (int i = 0; i < openCount - closeCount; i++) {
      sb.append("</em>");
    }

    return sb.toString();
  }
}
