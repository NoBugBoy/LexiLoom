package io.lexiloom.application.ai.search.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多工作空间配额计算与补偿计划 (充血值对象)
 *
 * <p>根据调用方指定的权重字典将 targetTopK 比例分配至各空间；未分配的余数以及未满足配额的缺额自动补偿给主空间。
 */
public record WorkspaceQuotaPlan(
    Long primaryWorkspaceId, Map<Long, Integer> quotas, int targetTopK) {

  public WorkspaceQuotaPlan {
    quotas = Map.copyOf(quotas);
  }

  public static WorkspaceQuotaPlan calculate(
      int targetTopK, Map<Long, Integer> weights, List<WorkspaceSource> sources, Long primaryId) {

    Map<Long, Integer> allocated = new HashMap<>();
    if (weights == null || weights.isEmpty()) {
      allocated.put(primaryId, targetTopK);
      return new WorkspaceQuotaPlan(primaryId, allocated, targetTopK);
    }

    Map<Long, Integer> validWeights = new HashMap<>();
    int sum = 0;
    for (WorkspaceSource source : sources) {
      Integer w = weights.get(source.workspaceId());
      if (w != null && w > 0) {
        validWeights.put(source.workspaceId(), w);
        sum += w;
      } else {
        validWeights.put(source.workspaceId(), 0);
      }
    }

    if (sum <= 0) {
      allocated.put(primaryId, targetTopK);
      return new WorkspaceQuotaPlan(primaryId, allocated, targetTopK);
    }

    int allocatedSum = 0;
    for (WorkspaceSource source : sources) {
      int w = validWeights.getOrDefault(source.workspaceId(), 0);
      int quota = (int) Math.floor((double) targetTopK * w / sum);
      allocated.put(source.workspaceId(), quota);
      allocatedSum += quota;
    }

    int remainder = targetTopK - allocatedSum;
    if (remainder > 0) {
      allocated.put(primaryId, allocated.getOrDefault(primaryId, 0) + remainder);
    }

    return new WorkspaceQuotaPlan(primaryId, allocated, targetTopK);
  }

  public int getQuota(Long workspaceId) {
    return quotas.getOrDefault(workspaceId, 0);
  }

  public int calculateCompensatedPrimaryQuota(int totalDeficit) {
    return quotas.getOrDefault(primaryWorkspaceId, targetTopK) + totalDeficit;
  }
}
