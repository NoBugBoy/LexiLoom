package com.knowflow.application.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowflow.application.document.search.DocSearchDocument;
import com.knowflow.application.document.service.impl.SearchSuggestQueryServiceImpl;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;

@ExtendWith(MockitoExtension.class)
@DisplayName("SearchSuggestQueryService 自动补全服务测试")
class SearchSuggestQueryServiceTest {

  @Mock private ElasticsearchOperations elasticsearchOperations;

  @InjectMocks private SearchSuggestQueryServiceImpl suggestQueryService;

  // ─────────────────────────────────────────────
  // 辅助：mock SearchHit 内容
  // ─────────────────────────────────────────────
  @SuppressWarnings("unchecked")
  private SearchHit<DocSearchDocument> mockHit(String id, String title) {
    SearchHit<DocSearchDocument> hit = mock(SearchHit.class);
    DocSearchDocument doc = DocSearchDocument.builder().id(id).title(title).build();
    when(hit.getContent()).thenReturn(doc);
    return hit;
  }

  // ─────────────────────────────────────────────
  // 辅助：mock SearchHits
  // ─────────────────────────────────────────────
  @SuppressWarnings("unchecked")
  private SearchHits<DocSearchDocument> mockSearchHits(List<SearchHit<DocSearchDocument>> hits) {
    SearchHits<DocSearchDocument> searchHits = mock(SearchHits.class);
    when(searchHits.getSearchHits()).thenReturn(hits);
    return searchHits;
  }

  @Test
  @DisplayName("搜索建议成功：根据关键词匹配返回最多 10 条候选词")
  void testSuggestSuccess() {
    SearchHit<DocSearchDocument> hit1 = mockHit("1", "Spring Cloud 微服务");
    SearchHit<DocSearchDocument> hit2 = mockHit("2", "Spring Boot 自动装配");

    SearchHits<DocSearchDocument> searchHits = mockSearchHits(List.of(hit1, hit2));

    when(elasticsearchOperations.search(any(NativeQuery.class), eq(DocSearchDocument.class)))
        .thenReturn(searchHits);

    List<String> suggestions = suggestQueryService.suggest(1L, "Spring");

    assertThat(suggestions).containsExactly("Spring Cloud 微服务", "Spring Boot 自动装配");
  }

  @Test
  @DisplayName("非法入参：关键词为空或工作区为空时直接返回空列表")
  void testSuggestWithInvalidParams() {
    assertThat(suggestQueryService.suggest(null, "Spring")).isEmpty();
    assertThat(suggestQueryService.suggest(1L, "")).isEmpty();
    assertThat(suggestQueryService.suggest(1L, "   ")).isEmpty();

    // 消除 search() 重载歧义，显式指定参数类型
    verify(elasticsearchOperations, never()).search(any(NativeQuery.class), any(Class.class));
  }

  @Test
  @DisplayName("ES 查询异常时：优雅捕获并返回空列表")
  void testSuggestExceptionGraceful() {
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(DocSearchDocument.class)))
        .thenThrow(new RuntimeException("ES cluster error"));

    List<String> result = suggestQueryService.suggest(1L, "Spring");

    assertThat(result).isEmpty();
  }
}
