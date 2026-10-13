package com.knowflow.application.document.search.dto;

/** 检索结果排序规则枚举 */
public enum DocSearchSortBy {
  /** 文本相关度得分降序 (_score desc) */
  RELEVANCE,

  /** 发布时间倒序 (publishedAt desc) */
  PUBLISHED_AT
}
