package com.knowflow.application.document.search.dto;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/** 全文检索命中结果条目 VO (不可变 record) */
public record DocSearchItemVO(
    String id,
    String title,
    String summary,
    String hitSnippet,
    String categoryId,
    List<String> tags,
    String sourceType,
    LocalDateTime publishedAt) {
  public DocSearchItemVO {
    tags = tags == null ? Collections.emptyList() : List.copyOf(tags);
  }
}
