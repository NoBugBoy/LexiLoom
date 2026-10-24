package io.lexiloom.application.ai.search.domain;

import java.util.List;

/**
 * 工作空间检索源元数据 (值对象)
 *
 * @param workspaceId 归属工作空间 ID
 * @param sourceType 来源类型: "PRIMARY" (主空间), "CHILD_AUTO" (直系子空间), "EXPLICIT" (显式横向关联空间)
 * @param onlyPublic 是否强制仅检索 PUBLIC 可见切片 (直系子空间强制为 true)
 * @param allowedDocIds 显式授权的文档 ID 名单 (针对横向关联空间细粒度授权)
 */
public record WorkspaceSource(
    Long workspaceId, String sourceType, boolean onlyPublic, List<Long> allowedDocIds) {

  public WorkspaceSource {
    allowedDocIds = allowedDocIds != null ? List.copyOf(allowedDocIds) : List.of();
  }

  public boolean isPrimary(Long primaryId) {
    return workspaceId != null && workspaceId.equals(primaryId);
  }

  public boolean isExplicit() {
    return "EXPLICIT".equals(sourceType);
  }

  public boolean isChildAuto() {
    return "CHILD_AUTO".equals(sourceType);
  }

  public boolean hasNoAllowedDocs() {
    return isExplicit() && allowedDocIds.isEmpty();
  }
}
