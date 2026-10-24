package io.lexiloom.application.ai.api.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Token 消耗统计数据传输对象 (不可变 record)
 *
 * @param days 统计天数范围 (例如 7)
 * @param startTime 统计开始时间
 * @param endTime 统计结束时间
 * @param workspaceId 筛选的工作空间 ID (为 null 表示全局)
 * @param total 全周期综合汇总总计
 * @param modelBreakdown 全周期各模型单独消耗总计列表
 * @param dailyStats 最近 N 天按天统计明细列表 (按日期升序，供折线图渲染)
 */
public record TokenUsageStatsDTO(
    Integer days,
    LocalDateTime startTime,
    LocalDateTime endTime,
    Long workspaceId,
    TokenUsageSummaryDTO total,
    List<ModelTokenUsageDTO> modelBreakdown,
    List<DailyTokenUsageDTO> dailyStats) {

  /** 综合汇总指标数据传输对象 */
  public record TokenUsageSummaryDTO(
      long totalTokens,
      long promptTokens,
      long completionTokens,
      long embeddingTokens,
      long rerankTokens,
      long callCount) {}

  /** 单个模型消耗指标数据传输对象 */
  public record ModelTokenUsageDTO(
      String modelName,
      long totalTokens,
      long promptTokens,
      long completionTokens,
      long embeddingTokens,
      long rerankTokens,
      long callCount,
      double percentage) {}

  /** 单日 Token 消耗数据传输对象 (折线图数据节点) */
  public record DailyTokenUsageDTO(
      String date,
      long totalTokens,
      long promptTokens,
      long completionTokens,
      long embeddingTokens,
      long rerankTokens,
      long callCount,
      List<DailyModelTokenDTO> modelBreakdown) {}

  /** 单日单模型消耗数据传输对象 */
  public record DailyModelTokenDTO(
      String modelName,
      long totalTokens,
      long promptTokens,
      long completionTokens,
      long embeddingTokens,
      long rerankTokens,
      long callCount) {}
}
