package com.knowflow.application.document.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 更新分类请求对象 (不可变 record) */
public record DocCategoryUpdateRequest(
    @NotBlank(message = "分类名称不能为空") @Size(max = 100, message = "分类名称最多100字符") String name,
    Integer sortOrder) {}
