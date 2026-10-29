package io.lexiloom.application.ai.model.request;

import org.jspecify.annotations.Nullable;

/**
 * API Key 维度 Token 消耗统计查询请求入参
 *
 * @param apiKey 可选，指定具体 API Key (支持明文串或脱敏掩码，留空表示统计全部 API Key)
 * @param workspaceId 可选，工作空间 ID 过滤
 * @param period 聚合时间周期：DAILY (按日，默认) 或 MONTHLY (按月)
 * @param days 统计天数周期 (当 period 为 DAILY 时生效，默认 30 天，最大限制 365 天)
 * @param months 统计月数周期 (当 period 为 MONTHLY 时生效，默认 6 个月，最大限制 36 个月)
 */
public record ApiKeyTokenUsageQueryRequest(
    @Nullable String apiKey,
    @Nullable Long workspaceId,
    @Nullable String period,
    @Nullable Integer days,
    @Nullable Integer months) {

  /**
   * 判断是否为按月统计模式
   */
  public boolean isMonthly() {
    return "MONTHLY".equalsIgnoreCase(period);
  }

  /**
   * 获取安全且合法的有效统计范围跨度
   *
   * @return 月数跨度或天数跨度
   */
  public int getEffectiveRange() {
    if (isMonthly()) {
      int m = (months != null && months > 0) ? months : 6;
      return Math.clamp(m, 1, 36);
    }
    int d = (days != null && days > 0) ? days : 30;
    return Math.clamp(d, 1, 365);
  }
}
