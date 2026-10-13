package com.knowflow.application.document.model.request;

/** 移动分类请求对象 (不可变 record) newParentId 为 null 或 0 时表示移动到根目录 */
public record DocCategoryMoveRequest(Long newParentId) {}
