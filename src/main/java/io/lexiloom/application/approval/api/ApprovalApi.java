package io.lexiloom.application.approval.api;

import io.lexiloom.application.approval.api.dto.ApprovalRecordDTO;
import io.lexiloom.application.enums.ApprovalTypeEnum;
import java.util.List;
import java.util.Optional;

/**
 * 统一审批对外服务 API
 */
public interface ApprovalApi {

  /** 提交空间跨域关联与文档精准授权审批单 */
  Long submitSpaceLinkApproval(
      Long spaceLinkId,
      Long sourceWorkspaceId,
      Long targetWorkspaceId,
      Long applicantId,
      String applicantName,
      String applyReason,
      List<Long> documentIds);

  /** 提交文档发布审批单 */
  Long submitDocumentAudit(
      Long documentId,
      Long workspaceId,
      Long applicantId,
      String applicantName,
      String applyReason,
      String extraData);

  /** 执行审批通过 (强制要求填写为什么同意的说明) */
  void approve(
      ApprovalTypeEnum type,
      Long bizId,
      Long approverId,
      String approverName,
      String approvalComment);

  /** 执行审批驳回 (强制要求填写为什么驳回的说明) */
  void reject(
      ApprovalTypeEnum type,
      Long bizId,
      Long approverId,
      String approverName,
      String approvalComment);

  /** 查询最新处于待审批状态的审批单 */
  Optional<ApprovalRecordDTO> getLatestPendingApproval(ApprovalTypeEnum type, Long bizId);

  /** 查询目标工作区下所有待审批单 */
  List<ApprovalRecordDTO> listPendingApprovals(Long targetWorkspaceId);

  /** 查询某业务实体所有流转审批记录 */
  List<ApprovalRecordDTO> listApprovalHistory(ApprovalTypeEnum type, Long bizId);
}
