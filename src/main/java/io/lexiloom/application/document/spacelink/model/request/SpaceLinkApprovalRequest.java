package io.lexiloom.application.document.spacelink.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 审批 SpaceLink 关联申请参数 (不可变 record) */
public record SpaceLinkApprovalRequest(
    @NotBlank(message = "审批动作不能为空")
        @Pattern(regexp = "(?i)APPROVE|REJECT", message = "审批动作仅支持 APPROVE 或 REJECT")
        String action,
    @NotBlank(message = "审批说明不能为空 (请说明为什么同意或为什么驳回)") @Size(max = 500, message = "审批说明最多500字符")
        String comment) {

  /** 兼容历史 reason 调用 */
  public String reason() {
    return this.comment;
  }
}
