package com.knowflow.application.document.controller;

import com.knowflow.application.document.api.dto.DocCategoryDTO;
import com.knowflow.application.document.api.dto.DocCategoryNodeDTO;
import com.knowflow.application.document.category.DocCategoryService;
import com.knowflow.application.document.model.request.DocCategoryCreateRequest;
import com.knowflow.application.document.model.request.DocCategoryMoveRequest;
import com.knowflow.application.document.model.request.DocCategoryUpdateRequest;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 文档分类目录树 REST 控制器 */
@RestController
@RequestMapping("/documents/categories")
@RequiredArgsConstructor
public class DocumentCategoryController {

  private final DocCategoryService docCategoryService;

  /** 创建分类目录 */
  @PostMapping
  public DocCategoryDTO create(@Validated @RequestBody DocCategoryCreateRequest request) {
    return docCategoryService.createCategory(
        request.workSpaceId(), request.parentId(), request.name(), request.sortOrder());
  }

  /** 更新分类名称与排序 */
  @PutMapping("/{id}")
  public DocCategoryDTO update(
      @PathVariable("id") Long id, @Validated @RequestBody DocCategoryUpdateRequest request) {
    return docCategoryService.updateCategory(id, request.name(), request.sortOrder());
  }

  /** 移动分类节点 */
  @PutMapping("/{id}/move")
  public DocCategoryDTO move(
      @PathVariable("id") Long id, @Validated @RequestBody DocCategoryMoveRequest request) {
    return docCategoryService.moveCategory(id, request.newParentId());
  }

  /** 删除分类 */
  @DeleteMapping("/{id}")
  public void delete(@PathVariable("id") Long id) {
    docCategoryService.deleteCategory(id);
  }

  /** 拉取完整分类树（包含各分类下关联文档数量） */
  @GetMapping("/tree")
  public List<DocCategoryNodeDTO> getTree(
      @RequestParam("workSpaceId") @NotNull(message = "工作空间ID不能为空") Long workSpaceId) {
    return docCategoryService.getCategoryTree(workSpaceId);
  }
}
