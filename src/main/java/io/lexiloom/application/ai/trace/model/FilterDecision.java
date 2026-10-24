package io.lexiloom.application.ai.trace.model;

/** 候选切片在初筛与精排流程中的准入决策 */
public enum FilterDecision {
  /** 最终入选并交付上下文 */
  ACCEPTED,
  /** 被过滤淘汰 */
  REJECTED,
  /** 因文档多样性限制暂存备选池 (后续若不足可触发回填) */
  BACKFILL
}
