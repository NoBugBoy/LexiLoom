package io.lexiloom.application.ai.api;

/**
 * AI 智脑对外状态与统计查询 API
 */
public interface AiStatsQueryApi {

  /**
   * 查询指定工作空间已入库索引的向量切片总数
   *
   * @param workspaceId 工作空间 ID
   * @return 向量切片总数
   */
  long countChunksByWorkspaceId(Long workspaceId);

  /**
   * AI 智脑核心检索总开关状态
   *
   * @return true 若已启用
   */
  boolean isAiEnabled();
}
