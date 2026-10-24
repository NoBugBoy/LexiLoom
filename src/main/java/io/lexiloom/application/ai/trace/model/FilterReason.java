package io.lexiloom.application.ai.trace.model;

/** 候选切片被过滤或降级的具体归因 */
public enum FilterReason {
  /** 正常通过，未被过滤 */
  NONE,
  /** 相似度低于最低准入阈值 (如得分 < minScoreThreshold) */
  BELOW_SIMILARITY_THRESHOLD,
  /** 单文档切片上限截断 (触发 maxChunksPerDocument 限制) */
  DIVERSITY_TRUNCATED,
  /** 跨路初筛去重淘汰 (切片在更高排名中已出现) */
  DUPLICATE_CHUNK,
  /** 细粒度文档 ACL 权限隔离拦截 */
  ACL_FORBIDDEN,
  /** 超过最终目标 Top-K 硬配额截断 */
  TOPK_QUOTA_EXCEEDED
}
