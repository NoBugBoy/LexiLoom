package com.knowflow.application.document.search.dto;

import java.util.Collections;
import java.util.List;

/** 全文检索分页响应 (不可变 record) */
public record DocumentSearchResponse(long total, int pages, List<DocSearchItemVO> items) {
  public DocumentSearchResponse {
    items = items == null ? Collections.emptyList() : List.copyOf(items);
  }

  public static DocumentSearchResponse of(long total, int pageSize, List<DocSearchItemVO> items) {
    int pages = pageSize <= 0 ? 0 : (int) Math.ceil((double) total / pageSize);
    return new DocumentSearchResponse(total, pages, items);
  }
}
