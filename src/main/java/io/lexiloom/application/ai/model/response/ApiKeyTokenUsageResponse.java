package io.lexiloom.application.ai.model.response;

import cn.hutool.core.date.DatePattern;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * API Key 维度 Token 消耗趋势统计响应模型 (不可变 record)
 *
 * @param periodType 聚合颗粒度：DAILY 或 MONTHLY
 * @param targetApiKey 指定查询的目标 API Key 脱敏串 (若查询全部则为 null)
 * @param workspaceId 工作空间 ID (若按工作空间筛选)
 * @param startTime 统计起始时间 (标准格式 yyyy-MM-dd HH:mm:ss)
 * @param endTime 统计截止时间 (标准格式 yyyy-MM-dd HH:mm:ss)
 * @param total 全周期综合汇总指标
 * @param keyBreakdown 各 API Key 在该周期内的总用量排行与占比
 * @param timeline 严格按时间升序排列的时间序列趋势数据 (供前端折线图/柱状图展示)
 */
public record ApiKeyTokenUsageResponse(
    String periodType,
    @Nullable String targetApiKey,
    @Nullable Long workspaceId,
    @JsonFormat(pattern = DatePattern.NORM_DATETIME_PATTERN) LocalDateTime startTime,
    @JsonFormat(pattern = DatePattern.NORM_DATETIME_PATTERN) LocalDateTime endTime,
    TokenUsageSummaryVO total,
    List<KeyBreakdownVO> keyBreakdown,
    List<TimelineTokenUsageVO> timeline) {

  /** 综合汇总指标视图对象 */
  public record TokenUsageSummaryVO(
      long totalTokens,
      long promptTokens,
      long completionTokens,
      long embeddingTokens,
      long rerankTokens,
      long callCount) {}

  /** 单个 API Key 汇总排行视图对象 */
  public record KeyBreakdownVO(
      String apiKey,
      long totalTokens,
      long promptTokens,
      long completionTokens,
      long callCount,
      double percentage) {}

  /** 时间序列节点统计视图对象 (按日或按月节点) */
  public record TimelineTokenUsageVO(
      String period,
      long totalTokens,
      long promptTokens,
      long completionTokens,
      long embeddingTokens,
      long rerankTokens,
      long callCount,
      List<KeyDetailVO> keyDetails) {}

  /** 单个时间节点下具体 Key 的用量明细 */
  public record KeyDetailVO(
      String apiKey,
      long totalTokens,
      long promptTokens,
      long completionTokens,
      long callCount) {}
}
