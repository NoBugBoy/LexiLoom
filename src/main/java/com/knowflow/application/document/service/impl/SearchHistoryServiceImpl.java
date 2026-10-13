package com.knowflow.application.document.service.impl;

import cn.hutool.core.util.StrUtil;
import com.knowflow.application.document.search.dto.SearchHistoryRecordEvent;
import com.knowflow.application.document.service.SearchHistoryService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 用户搜索历史服务实现
 *
 * <p>基于 Redis ZSet 高性能热点存储，保留最新 20 条并设置 30 天 TTL； 支持 workspaceId
 * 多租户隔离，并发布异步领域事件解耦落库，保证即便缓存故障历史仍可靠归档。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchHistoryServiceImpl implements SearchHistoryService {

  private static final String HISTORY_KEY_PREFIX = "kf:search:history:";
  private static final int MAX_HISTORY_ITEMS = 20;
  private static final long HISTORY_TTL_DAYS = 30L;

  private final StringRedisTemplate stringRedisTemplate;
  private final ApplicationEventPublisher eventPublisher;

  @Async
  @Override
  public void record(Long userId, Long workspaceId, String keyword) {
    if (userId == null || StrUtil.isBlank(keyword)) {
      return;
    }
    String cleanKeyword = keyword.trim();
    String key = buildKey(userId, workspaceId);

    try {
      double score = System.currentTimeMillis();
      stringRedisTemplate.opsForZSet().add(key, cleanKeyword, score);

      // 保留最新 20 条记录 (移除多余历史记录)
      stringRedisTemplate.opsForZSet().removeRange(key, 0, -(MAX_HISTORY_ITEMS + 1));

      // 设置 30 天 TTL，防止冷数据永久滞留 Redis
      stringRedisTemplate.expire(key, HISTORY_TTL_DAYS, TimeUnit.DAYS);
    } catch (Exception ex) {
      log.warn(
          "写入 Redis 搜索历史异常: userId={}, workspaceId={}, keyword={}, error={}",
          userId,
          workspaceId,
          cleanKeyword,
          ex.getMessage());
    } finally {
      // 解耦：即便 Redis 异常，仍可靠发布领域事件归档入库，防止搜索历史丢失
      try {
        eventPublisher.publishEvent(
            new SearchHistoryRecordEvent(userId, workspaceId, cleanKeyword, LocalDateTime.now()));
      } catch (Exception e) {
        log.warn(
            "发布搜索历史归档事件异常: userId={}, keyword={}, error={}", userId, cleanKeyword, e.getMessage());
      }
    }
  }

  @Override
  public List<String> getHistory(Long userId, Long workspaceId, int size) {
    if (userId == null) {
      return Collections.emptyList();
    }
    String key = buildKey(userId, workspaceId);
    int limit = size <= 0 ? 10 : Math.min(size, MAX_HISTORY_ITEMS);

    try {
      Set<String> members = stringRedisTemplate.opsForZSet().reverseRange(key, 0, limit - 1);
      return members == null ? Collections.emptyList() : new ArrayList<>(members);
    } catch (Exception ex) {
      log.warn(
          "读取 Redis 搜索历史异常: userId={}, workspaceId={}, error={}",
          userId,
          workspaceId,
          ex.getMessage());
      return Collections.emptyList();
    }
  }

  @Override
  public void delete(Long userId, Long workspaceId, String keyword) {
    if (userId == null || StrUtil.isBlank(keyword)) {
      return;
    }
    String key = buildKey(userId, workspaceId);
    try {
      stringRedisTemplate.opsForZSet().remove(key, keyword.trim());
    } catch (Exception ex) {
      log.warn(
          "删除 Redis 搜索历史异常: userId={}, workspaceId={}, keyword={}, error={}",
          userId,
          workspaceId,
          keyword,
          ex.getMessage());
    }
  }

  @Override
  public void clear(Long userId, Long workspaceId) {
    if (userId == null) {
      return;
    }
    String key = buildKey(userId, workspaceId);
    try {
      stringRedisTemplate.delete(key);
    } catch (Exception ex) {
      log.warn(
          "清空 Redis 搜索历史异常: userId={}, workspaceId={}, error={}",
          userId,
          workspaceId,
          ex.getMessage());
    }
  }

  /**
   * 构建多租户隔离的 Redis Key
   *
   * @param userId 用户 ID
   * @param workspaceId 工作区 ID (可为空)
   * @return Redis 键名
   */
  private String buildKey(Long userId, Long workspaceId) {
    if (workspaceId != null) {
      return HISTORY_KEY_PREFIX + workspaceId + ":" + userId;
    }
    return HISTORY_KEY_PREFIX + userId;
  }
}
