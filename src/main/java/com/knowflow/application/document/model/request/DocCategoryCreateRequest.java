package com.knowflow.application.document.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 创建分类请求对象 (不可变 record) */
public record DocCategoryCreateRequest(
    @NotNull(message = "所属工作空间ID不能为空") Long workSpaceId,
    Long parentId,
    @NotBlank(message = "分类名称不能为空") @Size(max = 100, message = "分类名称最多100字符") String name,
    Integer sortOrder) {}
