package com.knowflow.application.document.search.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Collections;
import java.util.List;

/** 全文检索请求参数 (全不可变 record) */
public record DocumentSearchRequest(
    @NotBlank(message = "搜索关键词不能为空") String keyword,
    @NotNull(message = "工作区ID不能为空") Long workspaceId,
    Long categoryId,
    List<String> tags,
    Integer pageNum,
    Integer pageSize,
    DocSearchSortBy sortBy) {

  public DocumentSearchRequest {
    pageNum = (pageNum == null || pageNum < 1) ? 1 : pageNum;
    pageSize = (pageSize == null || pageSize <= 0) ? 20 : Math.min(pageSize, 100);
    sortBy = sortBy == null ? DocSearchSortBy.RELEVANCE : sortBy;
    tags = tags == null ? Collections.emptyList() : List.copyOf(tags);
    keyword = keyword == null ? "" : keyword.trim();
  }
}
