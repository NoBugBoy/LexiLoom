package io.lexiloom.application.document.spacelink.model.entity;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.common.BaseEntity;
import io.lexiloom.application.enums.SpaceLinkStatusEnum;
import io.lexiloom.application.exception.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 工作区单向横向关联聚合实体 (SpaceLink)
 *
 * <p>表示源工作区申请关联检索目标工作区的已授权文档（PUBLIC / INTERNAL）。 严格单向且需目标工作区管理员审批通过后生效。
 */
@Entity
@Table(
    name = "ll_workspace_space_link",
    comment = "工作区横向显式关联表",
    indexes = {
      @Index(name = "idx_space_link_source", columnList = "source_workspace_id"),
      @Index(name = "idx_space_link_target_status", columnList = "target_workspace_id, status")
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkSpaceSpaceLinkEntity extends BaseEntity {

  /** 发起方工作区 ID (获得检索权限方) */
  @Column(name = "source_workspace_id", comment = "发起方工作区ID(获得检索权限方)", nullable = false)
  private Long sourceWorkspaceId;

  /** 目标工作区 ID (被检索方，需审批其公开内容授权) */
  @Column(name = "target_workspace_id", comment = "目标工作区ID(被检索授权方)", nullable = false)
  private Long targetWorkspaceId;

  /** 关联审批状态 */
  @Enumerated(EnumType.STRING)
  @Column(comment = "关联状态(PENDING/APPROVED/REJECTED)", nullable = false, length = 20)
  private SpaceLinkStatusEnum status;

  /** 驳回原因说明 (可选) */
  @Column(name = "reject_reason", comment = "驳回原因说明", length = 500)
  private String rejectReason;

  /** 静态工厂：创建新的横向关联申请 (初始状态为 PENDING) */
  public static WorkSpaceSpaceLinkEntity create(Long sourceWorkspaceId, Long targetWorkspaceId) {
    Objects.requireNonNull(sourceWorkspaceId, "源工作区ID不能为空");
    Objects.requireNonNull(targetWorkspaceId, "目标工作区ID不能为空");
    if (sourceWorkspaceId.equals(targetWorkspaceId)) {
      throw BusinessException.badRequest("不能关联当前工作区自身");
    }

    WorkSpaceSpaceLinkEntity entity = new WorkSpaceSpaceLinkEntity();
    entity.sourceWorkspaceId = sourceWorkspaceId;
    entity.targetWorkspaceId = targetWorkspaceId;
    entity.status = SpaceLinkStatusEnum.PENDING;
    entity.rejectReason = null;
    return entity;
  }

  /** 审批通过：生效关联 */
  public void approve() {
    this.status = SpaceLinkStatusEnum.APPROVED;
    this.rejectReason = null;
  }

  /** 审批驳回：拒绝关联或由目标管理员撤销已生效关联 */
  public void reject(String reason) {
    this.status = SpaceLinkStatusEnum.REJECTED;
    this.rejectReason = StrUtil.isNotBlank(reason) ? reason.trim() : "管理员已驳回关联申请";
  }

  /** 重新申请：若此前被驳回，允许发起方重新进入待审批状态 */
  public void reapply() {
    if (this.status == SpaceLinkStatusEnum.APPROVED) {
      throw BusinessException.badRequest("当前关联已处于审批通过生效状态，无需重复申请");
    }
    this.status = SpaceLinkStatusEnum.PENDING;
    this.rejectReason = null;
  }

  /** 判断关联是否已生效 */
  public boolean isEffective() {
    return this.status == SpaceLinkStatusEnum.APPROVED;
  }
}
