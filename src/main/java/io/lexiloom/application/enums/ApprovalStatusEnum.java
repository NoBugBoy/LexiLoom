package io.lexiloom.application.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 审批单状态枚举
 */
@Getter
@RequiredArgsConstructor
public enum ApprovalStatusEnum {
  /** 待审批 */
  PENDING("待审批"),

  /** 审批通过已同意 */
  APPROVED("已同意"),

  /** 审批驳回拒绝 */
  REJECTED("已驳回"),

  /** 申请人主动撤销 */
  CANCELLED("已撤销");

  private final String description;
}
