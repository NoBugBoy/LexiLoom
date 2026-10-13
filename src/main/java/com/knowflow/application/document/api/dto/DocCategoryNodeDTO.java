package com.knowflow.application.document.api.dto;

import java.util.Collections;
import java.util.List;

/** 分类目录树节点数据传输对象 (不可变 record) */
public record DocCategoryNodeDTO(
    Long id,
    Long workSpaceId,
    Long parentId,
    String name,
    Integer sortOrder,
    Integer level,
    String path,
    long docCount,
    List<DocCategoryNodeDTO> children) {

  public DocCategoryNodeDTO {
    children =
        (children == null) ? Collections.emptyList() : Collections.unmodifiableList(children);
  }
}
