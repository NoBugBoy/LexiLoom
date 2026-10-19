package io.lexiloom.application.approval.api.dto;

import io.lexiloom.application.enums.ApprovalStatusEnum;
import io.lexiloom.application.enums.ApprovalTypeEnum;
import java.time.LocalDateTime;

/**
 * 统一审批记录传输对象 (不可变 record)
 */
public record ApprovalRecordDTO(
    Long id,
    ApprovalTypeEnum approvalType,
    Long bizId,
    Long workspaceId,
    Long targetWorkspaceId,
    Long applicantId,
    String applicantName,
    String applyReason,
    ApprovalStatusEnum status,
    Long approverId,
    String approverName,
    String approvalComment,
    LocalDateTime approvalTime,
    String extraData,
    LocalDateTime createTime) {}
