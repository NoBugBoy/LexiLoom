package io.lexiloom.application.document.api.dto;

import lombok.Getter;

/** 触发文档状态流转的领域事件 */
@Getter
public enum DocumentEventEnum {

  /** 提交审批：审批驳回 -> 待审核，或重提审核 */
  SUBMIT("提交审批"),

  /** 审批通过：待审核 -> 审批通过 */
  APPROVE("审批通过"),

  /** 审批驳回：待审核 -> 审批驳回 */
  REJECT("审批驳回");

  private final String description;

  DocumentEventEnum(String description) {
    this.description = description;
  }
}
