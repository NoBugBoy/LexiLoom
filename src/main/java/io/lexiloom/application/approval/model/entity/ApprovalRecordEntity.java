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
 * <p>遵循 DDD 充血模型设计，自闭合管理文档发布审批、跨空间横向关联及文档精准授权审批，强制约束审批动作附带决断说明（为什么同意 / 为什么驳回）。
 */
@Entity
@Table(
    name = "ll_approval_record",
    comment = "统一业务审批流记录表",
    indexes = {
      @Index(
          name = "idx_approval_target_status",
          columnList = "target_workspace_id, status, approval_type"),
      @Index(name = "idx_approval_biz", columnList = "approval_type, biz_id, status")
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApprovalRecordEntity extends BaseEntity {

  private static final int MAX_COMMENT_LENGTH = 500;

  /** 审批业务类型 */
  @Enumerated(EnumType.STRING)
  @Column(
      name = "approval_type",
      comment = "审批业务类型(DOCUMENT_AUDIT/SPACE_LINK)",
      nullable = false,
      length = 32)
  private ApprovalTypeEnum approvalType;

  /** 业务主键 ID (例如 documentId 或 spaceLinkId) */
  @Column(name = "biz_id", comment = "业务主键ID(documentId或spaceLinkId)", nullable = false)
  private Long bizId;

  /** 发起方/所属工作空间 ID */
  @Column(name = "workspace_id", comment = "发起方/所属工作空间ID", nullable = false)
  private Long workspaceId;

  /** 目标工作空间 ID (跨空间关联时为目标审核空间，内部审批可为空或本空间) */
  @Column(name = "target_workspace_id", comment = "目标工作空间ID")
  private Long targetWorkspaceId;

  /** 申请人用户 ID */
  @Column(name = "applicant_id", comment = "申请人用户ID", nullable = false)
  private Long applicantId;

  /** 申请人姓名 */
  @Column(name = "applicant_name", comment = "申请人姓名", length = 100)
  private String applicantName;

  /** 申请理由或背景描述 */
  @Column(name = "apply_reason", comment = "申请理由或背景描述", length = 500)
  private String applyReason;

  /** 当前审批状态 */
  @Enumerated(EnumType.STRING)
  @Column(
      comment = "当前审批状态(PENDING/APPROVED/REJECTED/CANCELLED)",
      nullable = false,
      length = 20)
  private ApprovalStatusEnum status;

  /** 实际审批人用户 ID */
  @Column(name = "approver_id", comment = "实际审批人用户ID")
  private Long approverId;

  /** 实际审批人姓名 */
  @Column(name = "approver_name", comment = "实际审批人姓名", length = 100)
  private String approverName;

  /** 审批决策意见（强制说明：为什么同意 / 为什么驳回） */
  @Column(name = "approval_comment", comment = "审批决策意见说明", length = 500)
  private String approvalComment;

  /** 审批完成时间 */
  @Column(name = "approval_time", comment = "审批完成时间")
  private LocalDateTime approvalTime;

  /** 扩展元数据 (JSON 字符串：如跨空间关联申请时选中的 documentIds 清单等) */
  @Column(name = "extra_data", comment = "扩展元数据JSON", columnDefinition = "TEXT")
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
    record.applyReason =
        normalizeText(
            StrUtil.blankToDefault(applyReason, "申请建立工作空间跨域文档检索关联"), "申请理由不能超过500个字符");
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
    record.applyReason =
        normalizeText(StrUtil.blankToDefault(applyReason, "提交文档发布审核"), "申请理由不能超过500个字符");
    record.status = ApprovalStatusEnum.PENDING;
    record.extraData = extraData;
    return record;
  }

  /** 审批通过 (必须提供审批通过说明) */
  public void approve(Long approverId, String approverName, String comment) {
    completeDecision(
        ApprovalStatusEnum.APPROVED,
        approverId,
        approverName,
        comment,
        "当前审批单状态不是待审批，无法执行审批: " + this.status,
        "审批通过必须填写说明（为什么同意）");
  }

  /** 审批驳回 (必须提供驳回原因) */
  public void reject(Long approverId, String approverName, String comment) {
    completeDecision(
        ApprovalStatusEnum.REJECTED,
        approverId,
        approverName,
        comment,
        "当前审批单状态不是待审批，无法执行驳回: " + this.status,
        "审批驳回必须填写原因（为什么驳回）");
  }

  /** 申请人主动撤回审批单 */
  public void cancel(Long operatorId) {
    cancel(operatorId, null);
  }

  /** 申请人主动撤回审批单（支持附带可选撤回备注） */
  public void cancel(Long operatorId, String comment) {
    if (this.status != ApprovalStatusEnum.PENDING) {
      throw BusinessException.badRequest("当前审批单已处理，无法撤回: " + this.status);
    }
    if (!Objects.equals(this.applicantId, operatorId)) {
      throw BusinessException.forbidden("仅申请人本人可撤回审批单");
    }
    this.status = ApprovalStatusEnum.CANCELLED;
    this.approvalComment =
        normalizeText(StrUtil.blankToDefault(comment, "申请人主动撤回审批单"), "撤回说明不能超过500个字符");
    this.approvalTime = LocalDateTime.now();
  }

  /**
   * 同一业务实体重新发起审批时，自动归档/取代历史残留的 PENDING 审批单，防止产生幽灵待办单
   *
   * @param reason 取代说明
   */
  public void supersede(String reason) {
    if (this.status != ApprovalStatusEnum.PENDING) {
      return;
    }
    this.status = ApprovalStatusEnum.CANCELLED;
    this.approvalComment =
        normalizeText(StrUtil.blankToDefault(reason, "业务重新发起审批，旧待审单自动失效"), "归档说明不能超过500个字符");
    this.approvalTime = LocalDateTime.now();
  }

  private void completeDecision(
      ApprovalStatusEnum targetStatus,
      Long approverId,
      String approverName,
      String comment,
      String invalidStateMessage,
      String blankCommentMessage) {
    if (this.status != ApprovalStatusEnum.PENDING) {
      throw BusinessException.badRequest(invalidStateMessage);
    }
    Objects.requireNonNull(approverId, "审批人 ID 不能为空");
    if (StrUtil.isBlank(comment)) {
      throw BusinessException.badRequest(blankCommentMessage);
    }

    this.status = targetStatus;
    this.approverId = approverId;
    this.approverName = approverName;
    this.approvalComment = normalizeText(comment, "审批描述最多500字符");
    this.approvalTime = LocalDateTime.now();
  }

  private static String normalizeText(String text, String tooLongMessage) {
    String trimmed = text.trim();
    if (trimmed.length() > MAX_COMMENT_LENGTH) {
      throw BusinessException.badRequest(tooLongMessage);
    }
    return trimmed;
  }
}
