package io.lexiloom.application.document.api.dto;

import lombok.Getter;

/** 文档全生命周期状态枚举（严格收敛为待审核、审批通过、审批驳回三态） */
@Getter
public enum DocumentStateEnum {

  /** 待审核：导入或修改提交后进入审核流 */
  PENDING_APPROVAL("待审核"),

  /** 审批通过：审核通过，正式生效并供检索 */
  APPROVED("审批通过"),

  /** 审批驳回：审核未通过，保留驳回理由供修改后重提 */
  REJECTED("审批驳回");

  private final String description;

  DocumentStateEnum(String description) {
    this.description = description;
  }
}
