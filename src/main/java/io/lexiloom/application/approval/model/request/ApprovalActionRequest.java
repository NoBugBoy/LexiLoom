package io.lexiloom.application.approval.model.request;

import io.lexiloom.application.enums.ApprovalTypeEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 通用审批决策请求 (不可变 record) */
public record ApprovalActionRequest(
    @NotNull(message = "审批业务类型不能为空") ApprovalTypeEnum approvalType,
    @NotNull(message = "业务关联主键 bizId 不能为空") Long bizId,
    @NotBlank(message = "审批动作 action 不能为空 (APPROVE 或 REJECT)") String action,
    @NotBlank(message = "审批描述说明不能为空 (为什么同意/为什么驳回)") @Size(max = 500, message = "审批描述最多500字符")
        String comment) {}
