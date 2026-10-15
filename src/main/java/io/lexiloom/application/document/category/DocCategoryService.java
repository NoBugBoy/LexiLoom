package io.lexiloom.application.document.category;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.common.ErrorCode;
import io.lexiloom.application.document.api.dto.DocCategoryDTO;
import io.lexiloom.application.document.api.dto.DocCategoryNodeDTO;
import io.lexiloom.application.document.mapper.DocCategoryMapper;
import io.lexiloom.application.document.model.entity.DocCategoryEntity;
import io.lexiloom.application.document.repository.DocCategoryRepository;
import io.lexiloom.application.document.repository.DocumentRepository;
import io.lexiloom.application.exception.BusinessException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 文档分类目录树领域服务 负责分类多级树状维护、物化路径计算、防循环嵌套移动与树结构渲染 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocCategoryService {

  private final DocCategoryRepository docCategoryRepository;
  private final DocumentRepository documentRepository;
  private final DocCategoryMapper docCategoryMapper;

  /** 创建分类节点 */
  @Transactional
  public DocCategoryDTO createCategory(
      Long workSpaceId, Long parentId, String name, Integer sortOrder) {
    Long pid = (parentId == null || parentId < 0) ? 0L : parentId;

    if (docCategoryRepository.existsByWorkSpaceIdAndParentIdAndName(workSpaceId, pid, name)) {
      throw BusinessException.badRequest("当前层级下已存在同名分类: " + name);
    }

    String parentPath = "/0/";
    int parentLevel = 0;

    if (pid > 0) {
      DocCategoryEntity parent =
          docCategoryRepository
              .findById(pid)
              .orElseThrow(() -> new BusinessException(ErrorCode.Document.DOC_NOT_FOUND, "父分类不存在"));
      parentPath = parent.getPath();
      parentLevel = parent.getLevel();

      if (parentLevel >= 10) {
        throw BusinessException.badRequest("分类层级最多支持 10 层");
      }
    }

    DocCategoryEntity entity =
        DocCategoryEntity.create(workSpaceId, pid, name, sortOrder, parentPath, parentLevel);
    DocCategoryEntity saved = docCategoryRepository.save(entity);
    saved.refreshSelfPath();
    saved = docCategoryRepository.save(saved);

    log.info(
        "【DocCategoryService】分类创建成功: id={}, name={}, path={}",
        saved.getId(),
        saved.getName(),
        saved.getPath());

    return docCategoryMapper.toDTO(saved);
  }

  /** 重命名或调整排序 */
  @Transactional
  public DocCategoryDTO updateCategory(Long categoryId, String newName, Integer newSortOrder) {
    DocCategoryEntity category =
        docCategoryRepository
            .findById(categoryId)
            .orElseThrow(() -> new BusinessException(ErrorCode.Document.DOC_NOT_FOUND, "分类不存在"));

    category.updateInfo(newName, newSortOrder);
    DocCategoryEntity saved = docCategoryRepository.save(category);
    return docCategoryMapper.toDTO(saved);
  }

  /** 移动分类节点（防环检测与子树物化路径级联更新） */
  @Transactional
  public DocCategoryDTO moveCategory(Long categoryId, Long newParentId) {
    DocCategoryEntity category =
        docCategoryRepository
            .findById(categoryId)
            .orElseThrow(() -> new BusinessException(ErrorCode.Document.DOC_NOT_FOUND, "分类不存在"));

    Long targetParentId = (newParentId == null || newParentId < 0) ? 0L : newParentId;

    if (Objects.equals(category.getId(), targetParentId)) {
      throw BusinessException.badRequest("不能将分类移动到自己内部");
    }

    String oldPath = category.getPath();
    String newParentPath = "/0/";
    int newLevel = 1;

    if (targetParentId > 0) {
      DocCategoryEntity newParent =
          docCategoryRepository
              .findById(targetParentId)
              .orElseThrow(
                  () -> new BusinessException(ErrorCode.Document.DOC_NOT_FOUND, "目标父分类不存在"));

      // 防跨团队空间挂载
      if (!newParent.getWorkSpaceId().equals(category.getWorkSpaceId())) {
        throw BusinessException.badRequest("不能跨团队空间移动分类");
      }

      // 防环：不能移动到自己现有的子节点下
      if (newParent.getPath().startsWith(oldPath)) {
        throw BusinessException.badRequest("不能将分类移动到自身的子分类下");
      }

      newParentPath = newParent.getPath();
      newLevel = newParent.getLevel() + 1;

      if (newLevel > 10) {
        throw BusinessException.badRequest("分类层级最多支持 10 层");
      }
    }

    // 更新自身
    category.move(targetParentId, newParentPath, newLevel);
    DocCategoryEntity saved = docCategoryRepository.save(category);
    String newPath = saved.getPath();

    // 级联刷新所有子节点的 path 和 level
    List<DocCategoryEntity> subTree =
        docCategoryRepository.findByWorkSpaceIdAndPathStartingWith(
            category.getWorkSpaceId(), oldPath);

    int levelOffset = newLevel - (category.getLevel());
    for (DocCategoryEntity child : subTree) {
      if (!child.getId().equals(category.getId())) {
        String updatedChildPath = child.getPath().replaceFirst(oldPath, newPath);
        child.move(
            child.getParentId(),
            StrUtil.subBefore(updatedChildPath, child.getId() + "/", false),
            child.getLevel() + levelOffset);
        docCategoryRepository.save(child);
      }
    }

    log.info(
        "【DocCategoryService】分类移动成功: id={}, newParentId={}, newPath={}",
        saved.getId(),
        targetParentId,
        newPath);

    return docCategoryMapper.toDTO(saved);
  }

  /** 删除分类 */
  @Transactional
  public void deleteCategory(Long categoryId) {
    DocCategoryEntity category =
        docCategoryRepository
            .findById(categoryId)
            .orElseThrow(() -> new BusinessException(ErrorCode.Document.DOC_NOT_FOUND, "分类不存在"));

    long subCatCount =
        docCategoryRepository
            .findByWorkSpaceIdAndParentIdOrderBySortOrderAsc(category.getWorkSpaceId(), categoryId)
            .size();
    if (subCatCount > 0) {
      throw BusinessException.badRequest("该分类下包含子分类，请先清理或移出子分类");
    }

    long docCount =
        documentRepository.countByWorkSpaceIdAndCategoryId(category.getWorkSpaceId(), categoryId);
    if (docCount > 0) {
      throw BusinessException.badRequest("该分类下关联了 " + docCount + " 篇文档，请先移出文档后再删除");
    }

    docCategoryRepository.delete(category);
    log.info("【DocCategoryService】分类已删除: id={}", categoryId);
  }

  /** 递归组装分类树（含各分类下文档统计） */
  @Transactional(readOnly = true)
  public List<DocCategoryNodeDTO> getCategoryTree(Long workSpaceId) {
    List<DocCategoryEntity> allCategories =
        docCategoryRepository.findByWorkSpaceIdOrderBySortOrderAsc(workSpaceId);

    if (allCategories.isEmpty()) {
      return Collections.emptyList();
    }

    // 消除 N+1 查询：单次批量聚合分组统计各分类下文档数
    List<Object[]> countList = documentRepository.countGroupByCategoryId(workSpaceId);
    Map<Long, Long> docCountMap =
        countList != null
            ? countList.stream()
                .filter(r -> r != null && r[0] != null)
                .collect(Collectors.toMap(r -> (Long) r[0], r -> ((Number) r[1]).longValue()))
            : Collections.emptyMap();

    // 按 parentId 分组
    Map<Long, List<DocCategoryEntity>> parentMap =
        allCategories.stream().collect(Collectors.groupingBy(DocCategoryEntity::getParentId));

    return buildTreeNodes(0L, parentMap, workSpaceId, docCountMap);
  }

  private List<DocCategoryNodeDTO> buildTreeNodes(
      Long currentParentId,
      Map<Long, List<DocCategoryEntity>> parentMap,
      Long workSpaceId,
      Map<Long, Long> docCountMap) {
    List<DocCategoryEntity> children =
        parentMap.getOrDefault(currentParentId, Collections.emptyList());
    List<DocCategoryNodeDTO> result = new ArrayList<>();

    for (DocCategoryEntity cat : children) {
      long docCount = docCountMap.getOrDefault(cat.getId(), 0L);
      List<DocCategoryNodeDTO> subChildren =
          buildTreeNodes(cat.getId(), parentMap, workSpaceId, docCountMap);

      result.add(
          new DocCategoryNodeDTO(
              cat.getId(),
              cat.getWorkSpaceId(),
              cat.getParentId(),
              cat.getName(),
              cat.getSortOrder(),
              cat.getLevel(),
              cat.getPath(),
              docCount,
              subChildren));
    }
    return result;
  }
}
