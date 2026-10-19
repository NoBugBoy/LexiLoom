package io.lexiloom.application.ai.service.impl;

import io.lexiloom.application.ai.api.AiStatsQueryApi;
import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.repository.DocChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 智脑状态与统计查询实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiStatsQueryApiImpl implements AiStatsQueryApi {

  private final DocChunkRepository docChunkRepository;
  private final AiProperties aiProperties;

  @Override
  public long countChunksByWorkspaceId(Long workspaceId) {
    if (workspaceId == null) {
      return 0L;
    }
    try {
      return docChunkRepository.countByWorkspaceId(String.valueOf(workspaceId));
    } catch (Exception e) {
      log.warn("【AiStatsQueryApi】查询 ES 空间切片数异常: wsId={}, error={}", workspaceId, e.getMessage());
      return 0L;
    }
  }

  @Override
  public boolean isAiEnabled() {
    return aiProperties != null && aiProperties.isEnabled();
  }
}
