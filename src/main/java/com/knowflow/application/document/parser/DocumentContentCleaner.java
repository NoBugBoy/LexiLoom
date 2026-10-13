package com.knowflow.application.document.parser;

import cn.hutool.core.util.StrUtil;
import java.util.regex.Pattern;

/**
 * 文档 Markdown 解析内容清洗器
 *
 * <p>过滤文档解析引擎产出的无效内嵌图片（Base64 data URI、本地死链等）， 防止污染正文展示、词数统计以及 Elasticsearch 全文索引。
 */
public final class DocumentContentCleaner {

  private DocumentContentCleaner() {}

  // 1. 匹配 HTML data URI 格式的图片标签: <img ... src="data:..." ...>
  private static final Pattern HTML_DATA_IMAGE_PATTERN =
      Pattern.compile("<img\\b[^>]*src=[\"']data:[^\"']*[\"'][^>]*>", Pattern.CASE_INSENSITIVE);

  // 2. 匹配 HTML 非合法来源图片 (保留 http://, https://, /static/)
  private static final Pattern HTML_INVALID_IMAGE_PATTERN =
      Pattern.compile(
          "<img\\b(?![^>]*\\bsrc=[\"'](?:https?://|/static/))[^>]*>",
          Pattern.CASE_INSENSITIVE);

  // 3. 匹配 Markdown data URI 格式的图片（包括占位符 ![](data:image/png;base64...) 以及真实 Base64）
  private static final Pattern MD_DATA_IMAGE_PATTERN =
      Pattern.compile("!\\[.*?\\]\\(data:[^)]*\\)", Pattern.CASE_INSENSITIVE);

  // 4. 匹配 Markdown 本地死链图片 (保留 http://, https://, /static/)
  private static final Pattern MD_INVALID_IMAGE_PATTERN =
      Pattern.compile(
          "!\\[.*?\\]\\((?!(?:https?://|/static/))[^)]*\\)",
          Pattern.CASE_INSENSITIVE);

  // 5. 匹配行尾多余空白符与 3 个及以上连续换行符（用于空行折叠）
  private static final Pattern TRAILING_SPACES_PATTERN = Pattern.compile("[ \\t]+\\n");
  private static final Pattern MULTI_NEWLINE_PATTERN = Pattern.compile("\\n{3,}");

  // 提取纯文本辅助正则 (用于全文检索与分词)
  private static final Pattern MD_CODE_BLOCK_PATTERN = Pattern.compile("```[\\s\\S]*?```");
  private static final Pattern MD_ALL_IMAGE_PATTERN = Pattern.compile("!\\[.*?\\]\\(.*?\\)");
  private static final Pattern MD_LINK_PATTERN = Pattern.compile("\\[(.*?)\\]\\(.*?\\)");
  private static final Pattern MD_FORMATTING_PATTERN = Pattern.compile("[#*`_~]");

  /**
   * 清洗 Markdown 文本中的内嵌/无效图片，并规范化空白换行
   * 保留正规外链图片（http/https）及静态图解图片（/static/figures/...）与普通超链接
   *
   * @param markdown 原始 Markdown 字符串
   * @return 清洗后的纯净 Markdown 正文
   */
  public static String cleanEmbeddedImages(String markdown) {
    if (StrUtil.isBlank(markdown)) {
      return "";
    }
    // 过滤 HTML data 图片和非法图片
    String cleaned = HTML_DATA_IMAGE_PATTERN.matcher(markdown).replaceAll("");
    cleaned = HTML_INVALID_IMAGE_PATTERN.matcher(cleaned).replaceAll("");

    // 过滤 Markdown data 图片及非法死链图片
    cleaned = MD_DATA_IMAGE_PATTERN.matcher(cleaned).replaceAll("");
    cleaned = MD_INVALID_IMAGE_PATTERN.matcher(cleaned).replaceAll("");

    // 规范化空行：清理行尾无用空格，并将连续多余换行折叠为标准双换行
    cleaned = TRAILING_SPACES_PATTERN.matcher(cleaned).replaceAll("\n");
    cleaned = MULTI_NEWLINE_PATTERN.matcher(cleaned).replaceAll("\n\n");

    return cleaned.trim();
  }

  /**
   * 从 Markdown 中提取干净的纯文本，用于搜索引擎索引或词数精准统计
   *
   * @param markdown Markdown 正文
   * @return 剥离排版标记后的纯文本内容
   */
  public static String extractRawText(String markdown) {
    if (StrUtil.isBlank(markdown)) {
      return "";
    }
    // 先清理内嵌垃圾图片
    String text = cleanEmbeddedImages(markdown);
    // 移除 DeepDoc 坐标标记 @@页码\tx0\tx1\ty0\ty1##
    text = DeepDocCoordinateUtil.clean(text);
    // 移除代码块
    text = MD_CODE_BLOCK_PATTERN.matcher(text).replaceAll("");
    // 移除其余所有图片标签
    text = MD_ALL_IMAGE_PATTERN.matcher(text).replaceAll("");
    // 超链接保留锚文本: [标题](url) -> 标题
    text = MD_LINK_PATTERN.matcher(text).replaceAll("$1");
    // 移除 Markdown 特殊排版符号
    text = MD_FORMATTING_PATTERN.matcher(text).replaceAll("");
    // 折叠空白并去首尾空格
    return text.replaceAll("\\n+", "\n").trim();
  }

  /**
   * 彻底清洗 Markdown 文本中的 DeepDoc 坐标标记
   *
   * @param markdown 原始 Markdown
   * @return 抹除坐标标记后的 Markdown
   */
  public static String cleanCoordinates(String markdown) {
    return DeepDocCoordinateUtil.clean(markdown);
  }
}
