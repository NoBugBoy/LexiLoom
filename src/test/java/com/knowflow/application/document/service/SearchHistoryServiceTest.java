package com.knowflow.application.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowflow.application.document.search.dto.SearchHistoryRecordEvent;
import com.knowflow.application.document.service.impl.SearchHistoryServiceImpl;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

@ExtendWith(MockitoExtension.class)
@DisplayName("SearchHistoryService 搜索历史服务测试")
class SearchHistoryServiceTest {

  @Mock private StringRedisTemplate stringRedisTemplate;
  @Mock private ZSetOperations<String, String> zSetOperations;
  @Mock private ApplicationEventPublisher eventPublisher;

  @InjectMocks private SearchHistoryServiceImpl searchHistoryService;

  @BeforeEach
  void setUp() {
    lenient().when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
  }

  @Test
  @DisplayName("记录搜索历史：按工作区隔离写入 Redis ZSet 并设置 30 天 TTL，同时发布归档事件")
  void testRecordHistory() {
    Long userId = 1001L;
    Long workspaceId = 1L;
    String keyword = "Elasticsearch 8";

    searchHistoryService.record(userId, workspaceId, keyword);

    String expectedKey = "kf:search:history:1:1001";
    verify(zSetOperations).add(eq(expectedKey), eq("Elasticsearch 8"), anyDouble());
    verify(zSetOperations).removeRange(expectedKey, 0, -21);
    verify(stringRedisTemplate).expire(expectedKey, 30L, TimeUnit.DAYS);

    ArgumentCaptor<SearchHistoryRecordEvent> eventCaptor =
        ArgumentCaptor.forClass(SearchHistoryRecordEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());

    SearchHistoryRecordEvent capturedEvent = eventCaptor.getValue();
    assertThat(capturedEvent.userId()).isEqualTo(1001L);
    assertThat(capturedEvent.workspaceId()).isEqualTo(1L);
    assertThat(capturedEvent.keyword()).isEqualTo("Elasticsearch 8");
    assertThat(capturedEvent.searchAt()).isNotNull();
  }

  @Test
  @DisplayName("M2 容错保证：Redis 写入异常时仍可靠发布 DB 归档事件")
  void testRecordHistory_RedisFailureStillPublishesEvent() {
    Long userId = 1001L;
    Long workspaceId = 1L;
    String keyword = "Fault Tolerance";
    String expectedKey = "kf:search:history:1:1001";

    when(zSetOperations.add(eq(expectedKey), eq("Fault Tolerance"), anyDouble()))
        .thenThrow(new RuntimeException("Redis connection timeout"));

    // 执行不会向外抛异常
    searchHistoryService.record(userId, workspaceId, keyword);

    // 验证 DB 归档事件依然成功被发布
    ArgumentCaptor<SearchHistoryRecordEvent> eventCaptor =
        ArgumentCaptor.forClass(SearchHistoryRecordEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().keyword()).isEqualTo("Fault Tolerance");
  }

  @Test
  @DisplayName("获取搜索历史：多租户隔离倒序读取最近条数")
  void testGetHistory() {
    Long userId = 1002L;
    Long workspaceId = 2L;
    LinkedHashSet<String> mockSet = new LinkedHashSet<>(List.of("RAG", "ES", "Spring"));
    when(zSetOperations.reverseRange("kf:search:history:2:1002", 0, 9)).thenReturn(mockSet);

    List<String> history = searchHistoryService.getHistory(userId, workspaceId, 10);

    assertThat(history).containsExactly("RAG", "ES", "Spring");
  }

  @Test
  @DisplayName("删除单条搜索历史：调用 ZREM 并按工作区隔离")
  void testDeleteSingleHistory() {
    Long userId = 1003L;
    Long workspaceId = 1L;
    searchHistoryService.delete(userId, workspaceId, "废弃关键词");

    verify(zSetOperations).remove("kf:search:history:1:1003", "废弃关键词");
  }

  @Test
  @DisplayName("清空搜索历史：调用 DEL 并按工作区隔离")
  void testClearHistory() {
    Long userId = 1004L;
    Long workspaceId = 1L;
    searchHistoryService.clear(userId, workspaceId);

    verify(stringRedisTemplate).delete("kf:search:history:1:1004");
  }
}
