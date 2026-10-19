package io.lexiloom.application.approval.model.entity;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.common.BaseEntity;
import io.lexiloom.application.enums.ApprovalStatusEnum;
import io.lexiloom.application.enums.ApprovalTypeEnum;
import io.lexiloom.application.exception.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 统一通用审批流记录实体 (ApprovalRecord)
 *
 * <p>遵循 DDD 充血模型设计，自闭合管理文档发布审批、跨空间横向关联及文档精准授权审批，
 * 强制约束审批动作附带决断说明（为什么同意 / 为什么驳回）。
 */
@Entity
@Table(
    name = "ll_approval_record",
    comment = "统一业务审批流记录表",
    indexes = {
      @Index(name = "idx_approval_target_status", columnList = "target_workspace_id, status, approval_type"),
      @Index(name = "idx_approval_biz", columnList = "biz_id, approval_type")
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApprovalRecordEntity extends BaseEntity {

  /** 审批业务类型 */
  @Enumerated(EnumType.STRING)
  @Column(name = "approval_type", nullable = false, length = 32)
  private ApprovalTypeEnum approvalType;

  /** 业务主键 ID (例如 documentId 或 spaceLinkId) */
  @Column(name = "biz_id", nullable = false)
  private Long bizId;

  /** 发起方/所属工作空间 ID */
  @Column(name = "workspace_id", nullable = false)
  private Long workspaceId;

  /** 目标工作空间 ID (跨空间关联时为目标审核空间，内部审批可为空或本空间) */
  @Column(name = "target_workspace_id")
  private Long targetWorkspaceId;

  /** 申请人用户 ID */
  @Column(name = "applicant_id", nullable = false)
  private Long applicantId;

  /** 申请人姓名 */
  @Column(name = "applicant_name", length = 100)
  private String applicantName;

  /** 申请理由或背景描述 */
  @Column(name = "apply_reason", length = 500)
  private String applyReason;

  /** 当前审批状态 */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private ApprovalStatusEnum status;

  /** 实际审批人用户 ID */
  @Column(name = "approver_id")
  private Long approverId;

  /** 实际审批人姓名 */
  @Column(name = "approver_name", length = 100)
  private String approverName;

  /** 审批决策意见（强制说明：为什么同意 / 为什么驳回） */
  @Column(name = "approval_comment", length = 500)
  private String approvalComment;

  /** 审批完成时间 */
  @Column(name = "approval_time")
  private LocalDateTime approvalTime;

  /** 扩展元数据 (JSON 字符串：如跨空间关联申请时选中的 documentIds 清单等) */
  @Column(name = "extra_data", columnDefinition = "TEXT")
  private String extraData;

  /** 静态工厂：创建空间关联与文档授权审批单 */
  public static ApprovalRecordEntity createSpaceLinkApproval(
      Long spaceLinkId,
      Long sourceWorkspaceId,
      Long targetWorkspaceId,
      Long applicantId,
      String applicantName,
      String applyReason,
      String extraData) {
    Objects.requireNonNull(spaceLinkId, "关联主键 spaceLinkId 不能为空");
    Objects.requireNonNull(sourceWorkspaceId, "源工作空间 ID 不能为空");
    Objects.requireNonNull(targetWorkspaceId, "目标工作空间 ID 不能为空");
    Objects.requireNonNull(applicantId, "申请人 ID 不能为空");

    ApprovalRecordEntity record = new ApprovalRecordEntity();
    record.approvalType = ApprovalTypeEnum.SPACE_LINK;
    record.bizId = spaceLinkId;
    record.workspaceId = sourceWorkspaceId;
    record.targetWorkspaceId = targetWorkspaceId;
    record.applicantId = applicantId;
    record.applicantName = applicantName;
    record.applyReason = StrUtil.blankToDefault(applyReason, "申请建立工作空间跨域文档检索关联");
    record.status = ApprovalStatusEnum.PENDING;
    record.extraData = extraData;
    return record;
  }

  /** 静态工厂：创建文档发布审批单 */
  public static ApprovalRecordEntity createDocumentAudit(
      Long documentId,
      Long workspaceId,
      Long applicantId,
      String applicantName,
      String applyReason,
      String extraData) {
    Objects.requireNonNull(documentId, "文档 ID 不能为空");
    Objects.requireNonNull(workspaceId, "工作空间 ID 不能为空");
    Objects.requireNonNull(applicantId, "申请人 ID 不能为空");

    ApprovalRecordEntity record = new ApprovalRecordEntity();
    record.approvalType = ApprovalTypeEnum.DOCUMENT_AUDIT;
    record.bizId = documentId;
    record.workspaceId = workspaceId;
    record.targetWorkspaceId = workspaceId;
    record.applicantId = applicantId;
    record.applicantName = applicantName;
    record.applyReason = StrUtil.blankToDefault(applyReason, "提交文档发布审核");
    record.status = ApprovalStatusEnum.PENDING;
    record.extraData = extraData;
    return record;
  }

  /** 审批通过 (必须提供审批通过说明) */
  public void approve(Long approverId, String approverName, String comment) {
    if (this.status != ApprovalStatusEnum.PENDING) {
      throw BusinessException.badRequest("当前审批单状态不是待审批，无法执行审批: " + this.status);
    }
    Objects.requireNonNull(approverId, "审批人 ID 不能为空");
    if (StrUtil.isBlank(comment)) {
      throw BusinessException.badRequest("审批通过必须填写说明（为什么同意）");
    }

    this.status = ApprovalStatusEnum.APPROVED;
    this.approverId = approverId;
    this.approverName = approverName;
    this.approvalComment = comment.trim();
    this.approvalTime = LocalDateTime.now();
  }

  /** 审批驳回 (必须提供驳回原因) */
  public void reject(Long approverId, String approverName, String comment) {
    if (this.status != ApprovalStatusEnum.PENDING) {
      throw BusinessException.badRequest("当前审批单状态不是待审批，无法执行驳回: " + this.status);
    }
    Objects.requireNonNull(approverId, "审批人 ID 不能为空");
    if (StrUtil.isBlank(comment)) {
      throw BusinessException.badRequest("审批驳回必须填写原因（为什么驳回）");
    }

    this.status = ApprovalStatusEnum.REJECTED;
    this.approverId = approverId;
    this.approverName = approverName;
    this.approvalComment = comment.trim();
    this.approvalTime = LocalDateTime.now();
  }

  /** 申请人主动撤回审批单 */
  public void cancel(Long operatorId) {
    if (this.status != ApprovalStatusEnum.PENDING) {
      throw BusinessException.badRequest("当前审批单已处理，无法撤回: " + this.status);
    }
    if (!Objects.equals(this.applicantId, operatorId)) {
      throw BusinessException.forbidden("仅申请人本人可撤回审批单");
    }
    this.status = ApprovalStatusEnum.CANCELLED;
  }
}
