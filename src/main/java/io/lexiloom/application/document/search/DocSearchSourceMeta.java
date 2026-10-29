package io.lexiloom.application.document.search;

import java.util.List;

/**
 * 跨空间与多源检索拓扑元数据
 *
 * @param workspaceId 空间主键 ID
 * @param sourceType 检索来源类型 (PRIMARY / CHILD / SPACE_LINK)
 * @param onlyPublic 是否仅可见公开内容
 * @param allowedDocIds 显式授权的文档 ID 清单 (主要针对 SpaceLink 精准授权)
 */
public record DocSearchSourceMeta(
    Long workspaceId, String sourceType, boolean onlyPublic, List<Long> allowedDocIds) {}
