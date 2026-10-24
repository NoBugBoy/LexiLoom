package io.lexiloom.application.document.spacelink.model.entity;

import io.lexiloom.application.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 工作空间横向关联授权文档明细实体 (SpaceLinkDocument)
 *
 * <p>细粒度记录一次 SpaceLink 关联经审批通过后，允许被跨空间检索的目标空间具体文档。
 */
@Entity
@Table(
    name = "ll_workspace_space_link_doc",
    comment = "空间横向关联授权文档明细表",
    indexes = {
      @Index(name = "idx_space_link_doc_link", columnList = "link_id, document_id"),
      @Index(name = "idx_space_link_doc_target", columnList = "target_workspace_id, document_id")
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkSpaceSpaceLinkDocEntity extends BaseEntity {

  /** 关联主表 ID */
  @Column(name = "link_id", comment = "SpaceLink关联主表ID", nullable = false)
  private Long linkId;

  /** 目标工作空间 ID (被检索方空间) */
  @Column(name = "target_workspace_id", comment = "目标工作空间ID(被检索方空间)", nullable = false)
  private Long targetWorkspaceId;

  /** 被授权可供跨空间检索的目标文档 ID */
  @Column(name = "document_id", comment = "被授权可供跨空间检索的目标文档ID", nullable = false)
  private Long documentId;

  /** 静态工厂：创建单条文档授权记录 */
  public static WorkSpaceSpaceLinkDocEntity create(
      Long linkId, Long targetWorkspaceId, Long documentId) {
    Objects.requireNonNull(linkId, "关联主键 linkId 不能为空");
    Objects.requireNonNull(targetWorkspaceId, "目标工作空间 ID 不能为空");
    Objects.requireNonNull(documentId, "文档 ID 不能为空");

    WorkSpaceSpaceLinkDocEntity entity = new WorkSpaceSpaceLinkDocEntity();
    entity.linkId = linkId;
    entity.targetWorkspaceId = targetWorkspaceId;
    entity.documentId = documentId;
    return entity;
  }
}
