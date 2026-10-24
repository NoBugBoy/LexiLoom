package io.lexiloom.application.document.api;

import io.lexiloom.application.document.statemachine.DocumentStateEnum;

/**
 * 文档生命周期状态跨模块命令契约 (Spring Modulith 暴露)
 *
 * <p>为外部消费模块（如 AI 切片与向量化同步异常回滚）提供受控的状态流转与回滚接口。
 */
public interface DocumentStateApi {

  /**
   * 回滚/流转文档生命周期状态并发布领域事件通知下游同步
   *
   * @param documentId 文档主键 ID
   * @param targetState 目标状态 (如 PENDING_APPROVAL)
   * @param reason 回滚或流转原因
   */
  void rollbackState(Long documentId, DocumentStateEnum targetState, String reason);

  /** 累加文档向量化实际消耗的 Token 数。 */
  void recordEmbeddingTokens(Long documentId, int tokens);
}
