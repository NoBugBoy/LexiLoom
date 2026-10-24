package io.lexiloom.application.ai.model.response;

import cn.hutool.core.date.DatePattern;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Token 消耗统计对外响应视图对象 (不可变 record)
 *
 * @param days 统计天数范围 (默认 7 天)
 * @param startTime 统计开始时间 (格式 yyyy-MM-dd HH:mm:ss，防止带 T)
 * @param endTime 统计结束时间 (格式 yyyy-MM-dd HH:mm:ss，防止带 T)
 * @param workspaceId 工作空间 ID (若按空间筛选)
 * @param total 全周期综合汇总总计
 * @param modelBreakdown 全周期每种模型单独的消耗总计列表
 * @param dailyStats 最近 N 天按天统计明细列表 (按日期升序，专供前端折线图展示)
 */
public record TokenUsageStatsResponse(
    Integer days,
    @JsonFormat(pattern = DatePattern.NORM_DATETIME_PATTERN) LocalDateTime startTime,
    @JsonFormat(pattern = DatePattern.NORM_DATETIME_PATTERN) LocalDateTime endTime,
    Long workspaceId,
    TokenUsageSummaryVO total,
    List<ModelTokenUsageVO> modelBreakdown,
    List<DailyTokenUsageVO> dailyStats) {

  /** 综合汇总指标视图对象 */
  public record TokenUsageSummaryVO(
      long totalTokens,
      long promptTokens,
      long completionTokens,
      long embeddingTokens,
      long rerankTokens,
      long callCount) {}

  /** 单个模型消耗指标视图对象 */
  public record ModelTokenUsageVO(
      String modelName,
      long totalTokens,
      long promptTokens,
      long completionTokens,
      long embeddingTokens,
      long rerankTokens,
      long callCount,
      double percentage) {}

  /** 单日 Token 消耗视图对象 (前端折线图节点) */
  public record DailyTokenUsageVO(
      String date,
      long totalTokens,
      long promptTokens,
      long completionTokens,
      long embeddingTokens,
      long rerankTokens,
      long callCount,
      List<DailyModelTokenVO> modelBreakdown) {}

  /** 单日单模型消耗视图对象 */
  public record DailyModelTokenVO(
      String modelName,
      long totalTokens,
      long promptTokens,
      long completionTokens,
      long embeddingTokens,
      long rerankTokens,
      long callCount) {}
}
