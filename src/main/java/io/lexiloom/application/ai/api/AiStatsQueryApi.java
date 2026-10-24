package io.lexiloom.application.ai.api;

import io.lexiloom.application.ai.api.dto.TokenUsageStatsDTO;

/** AI 智脑对外状态与统计查询 API */
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

  /**
   * 统计指定周期内的 Token 消耗情况（包含全模型汇总及各模型单独总计）
   *
   * @param workspaceId 可选的工作空间 ID (为 null 表示统计全局)
   * @param days 统计天数周期 (若为 null 或 <= 0 则默认 7 天)
   * @return Token 消耗统计数据传输对象
   */
  TokenUsageStatsDTO getTokenUsageStats(Long workspaceId, Integer days);
}
