package com.knowflow.application.document.api.dto;

import java.time.LocalDateTime;

/** 分类目录数据传输对象 (不可变 record) */
public record DocCategoryDTO(
    Long id,
    Long workSpaceId,
    Long parentId,
    String name,
    Integer sortOrder,
    Integer level,
    String path,
    LocalDateTime createTime) {}
