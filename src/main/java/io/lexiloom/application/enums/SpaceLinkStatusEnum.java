package io.lexiloom.application.enums;

/** 工作区横向关联（SpaceLink）审批状态枚举 */
public enum SpaceLinkStatusEnum {
  /** 待审批 */
  PENDING,
  /** 审批通过（已生效，可纳入跨空间检索） */
  APPROVED,
  /** 审批驳回/已撤销 */
  REJECTED
}
