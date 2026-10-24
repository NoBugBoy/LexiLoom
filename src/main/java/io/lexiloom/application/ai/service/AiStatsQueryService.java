package io.lexiloom.application.ai.service;

import io.lexiloom.application.ai.model.response.TokenUsageStatsResponse;

/** AI 智脑内部统计查询服务接口 (服务于 Controller 及模块内编排) */
public interface AiStatsQueryService {

  /**
   * 统计指定周期内的 Token 消耗情况（包含全模型汇总及各模型单独总计）
   *
   * @param workspaceId 可选的工作空间 ID (为 null 表示统计全局)
   * @param days 统计天数周期 (若为 null 或 <= 0 则默认 7 天)
   * @return Token 消耗统计对外响应对象
   */
  TokenUsageStatsResponse getTokenUsageStatsResponse(Long workspaceId, Integer days);
}
