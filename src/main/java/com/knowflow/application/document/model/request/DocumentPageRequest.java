package com.knowflow.application.document.model.request;

import com.knowflow.application.document.statemachine.DocumentStateEnum;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** 文档分页检索请求对象 (不可变 record) */
public record DocumentPageRequest(
    @NotNull(message = "工作空间ID不能为空") Long workSpaceId,
    Long categoryId,
    DocumentStateEnum status,
    String keyword,
    Integer page,
    Integer size) {

  public DocumentPageRequest {
    page = (page == null || page < 0) ? 0 : page;
    size = (size == null || size <= 0) ? 20 : Math.min(size, 100);
  }

  public Pageable toPageable() {
    return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updateTime"));
  }
}
