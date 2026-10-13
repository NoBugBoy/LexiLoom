package com.knowflow.application.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.knowflow.application.document.model.entity.SearchHistoryEntity;
import com.knowflow.application.document.repository.SearchHistoryRepository;
import com.knowflow.application.document.search.dto.SearchHistoryRecordEvent;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("搜索历史归档实体与异步监听器测试")
class SearchHistoryArchiveTest {

  @Mock private SearchHistoryRepository searchHistoryRepository;

  @InjectMocks private SearchHistoryArchiveListener archiveListener;

  @Test
  @DisplayName("SearchHistoryEntity 静态工厂构建与属性校验")
  void testSearchHistoryEntityCreation() {
    LocalDateTime now = LocalDateTime.of(2026, 10, 1, 12, 0, 0);
    SearchHistoryEntity entity = SearchHistoryEntity.of(1001L, 1L, "  Elasticsearch  ", now);

    assertThat(entity.getUserId()).isEqualTo(1001L);
    assertThat(entity.getWorkspaceId()).isEqualTo(1L);
    assertThat(entity.getKeyword()).isEqualTo("Elasticsearch");
    assertThat(entity.getSearchAt()).isEqualTo(now);
  }

  @Test
  @DisplayName("SearchHistoryEntity 参数非空校验")
  void testSearchHistoryEntityValidation() {
    assertThatThrownBy(() -> SearchHistoryEntity.of(null, 1L, "关键词"))
        .isInstanceOf(NullPointerException.class);

    assertThatThrownBy(() -> SearchHistoryEntity.of(1L, null, "关键词"))
        .isInstanceOf(NullPointerException.class);

    assertThatThrownBy(() -> SearchHistoryEntity.of(1L, 1L, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("SearchHistoryArchiveListener 正常监听事件并异步落库")
  void shouldSaveSearchHistoryEntityOnEvent() {
    LocalDateTime now = LocalDateTime.now();
    SearchHistoryRecordEvent event = new SearchHistoryRecordEvent(2001L, 5L, "Spring Data ES", now);

    archiveListener.onSearchHistoryRecord(event);

    ArgumentCaptor<SearchHistoryEntity> captor = ArgumentCaptor.forClass(SearchHistoryEntity.class);
    verify(searchHistoryRepository).save(captor.capture());

    SearchHistoryEntity saved = captor.getValue();
    assertThat(saved.getUserId()).isEqualTo(2001L);
    assertThat(saved.getWorkspaceId()).isEqualTo(5L);
    assertThat(saved.getKeyword()).isEqualTo("Spring Data ES");
    assertThat(saved.getSearchAt()).isEqualTo(now);
  }

  @Test
  @DisplayName("非法或空事件时跳过处理")
  void shouldSkipOnNullEventOrFields() {
    archiveListener.onSearchHistoryRecord(null);
    archiveListener.onSearchHistoryRecord(
        new SearchHistoryRecordEvent(null, 1L, "key", LocalDateTime.now()));
    archiveListener.onSearchHistoryRecord(
        new SearchHistoryRecordEvent(1L, 1L, null, LocalDateTime.now()));

    verify(searchHistoryRepository, never()).save(any());
  }

  @Test
  @DisplayName("落库发生异常时不中断流程")
  void shouldHandleExceptionGracefully() {
    doThrow(new RuntimeException("DB connection error")).when(searchHistoryRepository).save(any());

    SearchHistoryRecordEvent event =
        new SearchHistoryRecordEvent(2002L, 5L, "异常测试", LocalDateTime.now());

    archiveListener.onSearchHistoryRecord(event);

    verify(searchHistoryRepository).save(any());
  }
}
