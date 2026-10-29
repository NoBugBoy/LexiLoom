package io.lexiloom.application.document.service;

import io.lexiloom.application.document.model.entity.SearchHistoryEntity;
import io.lexiloom.application.document.repository.SearchHistoryRepository;
import io.lexiloom.application.document.search.dto.SearchHistoryRecordEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 搜索历史异步归档监听器
 *
 * <p>监听 {@link SearchHistoryRecordEvent}，异步将用户搜索记录持久化落库至 DB 归档，防止 Redis 历史丢失。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchHistoryArchiveListener {

  private final SearchHistoryRepository searchHistoryRepository;

  @Async
  @EventListener
  public void onSearchHistoryRecord(SearchHistoryRecordEvent event) {
    if (event == null || event.userId() == null || event.keyword() == null) {
      return;
    }
    try {
      Long effectiveWorkspaceId = event.workspaceId() != null ? event.workspaceId() : 0L;
      SearchHistoryEntity entity =
          SearchHistoryEntity.of(
              event.userId(), effectiveWorkspaceId, event.keyword(), event.searchAt());
      searchHistoryRepository.save(entity);
      log.debug(
          "成功异步归档搜索历史: userId={}, workspaceId={}, keyword={}",
          event.userId(),
          event.workspaceId(),
          event.keyword());
    } catch (Exception ex) {
      log.warn("异步归档搜索历史异常 (不阻塞主流程): error={}", ex.getMessage());
    }
  }
}
