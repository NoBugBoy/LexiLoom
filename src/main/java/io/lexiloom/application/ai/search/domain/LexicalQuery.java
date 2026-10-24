package io.lexiloom.application.ai.search.domain;

import cn.hutool.core.util.StrUtil;
import java.util.regex.Pattern;

/**
 * 词法检索提问关键词清洗值对象 (LexicalQuery)
 *
 * <p>Dense 向量检索需保留完整自然语言语义，而 BM25 作为词袋 TF-IDF 算法，极易被自然语言中的提问疑问词、 指令助词（如“有哪些”、“要返回”、“请问”）严重稀释和污染。
 * 本对象负责剥离自然语言提问助词与口语虚词，提取纯净的技术核心词交付 BM25 检索。
 */
public record LexicalQuery(String rawQuery, String cleanedQuery) {

  private static final Pattern QUESTION_PATTERN =
      Pattern.compile(
          "(?i)(有哪些|有什么|是什么|怎么做|如何|为什么|请问|请告诉我|告诉我|请给出|请列出|要返回|需要返回|详细说明|能否|是否|帮我|帮忙|麻烦)");

  private static final Pattern MODAL_PARTICLE_PATTERN =
      Pattern.compile(
          "(?i)(?:^|\\s+|(?<=[\\u4e00-\\u9fa5a-zA-Z0-9]))(和|及|与|或|的|了|吗|呢|吧|啊|呀|嗯|哦|嘛|对吗|是吗)(?:$|\\s+|(?=[\\u4e00-\\u9fa5a-zA-Z0-9]))");

  private static final Pattern COLLOQUIAL_PATTERN =
      Pattern.compile("(?i)(给我看看|说一下|讲一下|介绍一下|展示一下|描述一下|分析一下)");

  private static final Pattern MULTI_WHITESPACE_PATTERN = Pattern.compile("\\s+");

  public static LexicalQuery of(String rawQuery) {
    if (StrUtil.isBlank(rawQuery)) {
      return new LexicalQuery("", "");
    }
    String cleaned = QUESTION_PATTERN.matcher(rawQuery).replaceAll(" ");
    cleaned = MODAL_PARTICLE_PATTERN.matcher(cleaned).replaceAll(" ");
    cleaned = COLLOQUIAL_PATTERN.matcher(cleaned).replaceAll(" ");
    cleaned = MULTI_WHITESPACE_PATTERN.matcher(cleaned).replaceAll(" ").trim();
    String finalCleaned = StrUtil.isNotBlank(cleaned) ? cleaned : rawQuery.trim();
    return new LexicalQuery(rawQuery.trim(), finalCleaned);
  }
}
