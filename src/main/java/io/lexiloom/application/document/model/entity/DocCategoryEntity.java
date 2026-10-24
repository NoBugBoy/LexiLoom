package io.lexiloom.application.document.model.entity;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.common.BaseEntity;
import io.lexiloom.application.exception.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 文档分类目录树实体 支持按工作区隔离的多级树状层级导航与物化路径高效查询 */
@Entity
@Table(
    name = "ll_doc_category",
    comment = "文档分类树表",
    indexes = {
      @Index(name = "idx_category_workspace", columnList = "work_space_id"),
      @Index(name = "idx_category_parent", columnList = "parent_id"),
      @Index(name = "idx_category_path", columnList = "path")
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocCategoryEntity extends BaseEntity {

  /** 所属团队空间 ID */
  @Column(name = "work_space_id", comment = "所属团队空间ID", nullable = false)
  private Long workSpaceId;

  /** 父分类 ID (0 表示顶级分类) */
  @Column(name = "parent_id", comment = "父分类ID(0为顶级分类)", nullable = false)
  private Long parentId;

  /** 分类名称 */
  @Column(comment = "分类名称", nullable = false, length = 100)
  private String name;

  /** 同级展示排序权重 (升序) */
  @Column(name = "sort_order", comment = "同级展示排序权重", nullable = false)
  private Integer sortOrder;

  /** 树深度层级 (1 为根节点层) */
  @Column(comment = "树深度层级(1为根节点)", nullable = false)
  private Integer level;

  /** 物化路径 (例如 /0/ 或 /0/1/5/，方便前缀匹配秒查整棵子树) */
  @Column(comment = "物化路径(如/0/1/5/)", nullable = false, length = 500)
  private String path;

  /** 静态工厂：创建分类节点 */
  public static DocCategoryEntity create(
      Long workSpaceId,
      Long parentId,
      String name,
      Integer sortOrder,
      String parentPath,
      Integer parentLevel) {
    Objects.requireNonNull(workSpaceId, "所属工作区不能为空");
    if (StrUtil.isBlank(name)) {
      throw BusinessException.badRequest("分类名称不能为空");
    }

    Long pid = (parentId == null || parentId < 0) ? 0L : parentId;
    int lvl = (parentLevel == null || parentLevel < 0) ? 1 : parentLevel + 1;
    String pPath = StrUtil.isNotBlank(parentPath) ? parentPath : "/0/";

    DocCategoryEntity entity = new DocCategoryEntity();
    entity.workSpaceId = workSpaceId;
    entity.parentId = pid;
    entity.name = name.trim();
    entity.sortOrder = (sortOrder == null) ? 0 : sortOrder;
    entity.level = lvl;
    // 自身 path 在保存获取 ID 后追加，初始保留父级 path 前缀
    entity.path = pPath.endsWith("/") ? pPath : pPath + "/";
    return entity;
  }

  /** 补充当前节点的自闭合路径（用于持久化拿到主键后刷新 path） */
  public void refreshSelfPath() {
    if (getId() != null && !this.path.endsWith("/" + getId() + "/")) {
      this.path = this.path + getId() + "/";
    }
  }

  /** 更新名称与排序 */
  public void updateInfo(String newName, Integer newSortOrder) {
    if (StrUtil.isNotBlank(newName)) {
      this.name = newName.trim();
    }
    if (newSortOrder != null) {
      this.sortOrder = newSortOrder;
    }
  }

  /** 移动节点到新的父分类下 */
  public void move(Long newParentId, String newParentPath, int newLevel) {
    this.parentId = (newParentId == null || newParentId < 0) ? 0L : newParentId;
    this.level = newLevel;
    String pPath = StrUtil.isNotBlank(newParentPath) ? newParentPath : "/0/";
    this.path =
        (pPath.endsWith("/") ? pPath : pPath + "/") + (getId() != null ? getId() + "/" : "");
  }
}
