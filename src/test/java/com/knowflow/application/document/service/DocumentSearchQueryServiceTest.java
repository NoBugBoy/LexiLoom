package com.knowflow.application.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowflow.application.common.SecurityHolder;
import com.knowflow.application.document.search.DocSearchDocument;
import com.knowflow.application.document.search.dto.DocSearchItemVO;
import com.knowflow.application.document.search.dto.DocSearchSortBy;
import com.knowflow.application.document.search.dto.DocumentSearchRequest;
import com.knowflow.application.document.search.dto.DocumentSearchResponse;
import com.knowflow.application.document.service.impl.DocumentSearchQueryServiceImpl;
import com.knowflow.application.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentSearchQueryService 检索服务测试")
class DocumentSearchQueryServiceTest {

  @Mock private ElasticsearchOperations elasticsearchOperations;
  @Mock private SearchHistoryService searchHistoryService;

  @InjectMocks private DocumentSearchQueryServiceImpl searchQueryService;

  @BeforeEach
  void setUp() {
    SecurityHolder.setAuthentication(12345L);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  // ─────────────────────────────────────────────
  // 辅助：构造 mock SearchHit
  // ─────────────────────────────────────────────
  @SuppressWarnings("unchecked")
  private SearchHit<DocSearchDocument> mockHit(
      String id, DocSearchDocument doc, Map<String, List<String>> highlights) {
    SearchHit<DocSearchDocument> hit = mock(SearchHit.class);
    lenient().when(hit.getId()).thenReturn(id); // getId() 仅部分路径调用，使用 lenient 避免 strict 误报
    when(hit.getContent()).thenReturn(doc);
    when(hit.getHighlightFields()).thenReturn(highlights);
    return hit;
  }

  // ─────────────────────────────────────────────
  // 辅助：构造 mock SearchHits
  // ─────────────────────────────────────────────
  @SuppressWarnings("unchecked")
  private SearchHits<DocSearchDocument> mockSearchHits(
      long total, List<SearchHit<DocSearchDocument>> hits) {
    SearchHits<DocSearchDocument> searchHits = mock(SearchHits.class);
    when(searchHits.getTotalHits()).thenReturn(total);
    when(searchHits.getSearchHits()).thenReturn(hits);
    return searchHits;
  }

  @Test
  @DisplayName("全文检索成功：正确提取高亮字段并异步记录搜索历史")
  void testSuccessfulSearchWithHighlightsAndAsyncHistory() {
    DocumentSearchRequest request =
        new DocumentSearchRequest(
            "Spring", 1L, 10L, List.of("后端"), 1, 20, DocSearchSortBy.RELEVANCE);

    LocalDateTime now = LocalDateTime.now();
    DocSearchDocument doc =
        DocSearchDocument.builder()
            .id("100")
            .workspaceId("1")
            .title("Spring Boot 3 实战")
            .summary("关于 Spring Boot 的全景指南")
            .content("正文原始 Markdown")
            .rawText("原文件提取纯文本")
            .categoryId("10")
            .tags(List.of("后端"))
            .sourceType("IMPORT")
            .publishedAt(now)
            .build();

    Map<String, List<String>> highlightFields =
        Map.of(
            "title", List.of("<em class=\"hl\">Spring</em> Boot 3 实战"),
            "summary", List.of("关于 <em class=\"hl\">Spring</em> 的全景指南"),
            "rawText", List.of("原文件中命中的 <em class=\"hl\">Spring</em> 片段"));

    SearchHit<DocSearchDocument> hit = mockHit("100", doc, highlightFields);
    SearchHits<DocSearchDocument> searchHits = mockSearchHits(1L, List.of(hit));

    ArgumentCaptor<NativeQuery> queryCaptor = ArgumentCaptor.forClass(NativeQuery.class);
    when(elasticsearchOperations.search(queryCaptor.capture(), eq(DocSearchDocument.class)))
        .thenReturn(searchHits);

    DocumentSearchResponse response = searchQueryService.search(request);

    // 1. 验证响应 DTO 属性与高亮提取
    assertThat(response.total()).isEqualTo(1);
    assertThat(response.pages()).isEqualTo(1);
    assertThat(response.items()).hasSize(1);

    DocSearchItemVO item = response.items().getFirst();
    assertThat(item.id()).isEqualTo("100");
    assertThat(item.title()).isEqualTo("<em class=\"hl\">Spring</em> Boot 3 实战");
    assertThat(item.summary()).isEqualTo("关于 <em class=\"hl\">Spring</em> 的全景指南");
    assertThat(item.hitSnippet()).isEqualTo("原文件中命中的 <em class=\"hl\">Spring</em> 片段");
    assertThat(item.categoryId()).isEqualTo("10");
    assertThat(item.tags()).containsExactly("后端");
    assertThat(item.publishedAt()).isEqualTo(now);

    // 2. 验证异步记录搜索历史被调用
    verify(searchHistoryService).record(12345L, 1L, "Spring");

    // 3. 验证构建的 Query 分页参数
    NativeQuery executedQuery = queryCaptor.getValue();
    assertThat(executedQuery).isNotNull();
    assertThat(executedQuery.getPageable().getPageNumber()).isEqualTo(0);
    assertThat(executedQuery.getPageable().getPageSize()).isEqualTo(20);
  }

  @Test
  @DisplayName("正文高亮回退：当无 rawText 高亮时优先使用 content 高亮片段")
  void testSearchSnippetFallbackToContentHighlight() {
    DocumentSearchRequest request =
        new DocumentSearchRequest("微服务", 2L, null, null, 1, 10, DocSearchSortBy.PUBLISHED_AT);

    DocSearchDocument doc =
        DocSearchDocument.builder()
            .id("101")
            .workspaceId("2")
            .title("微服务架构")
            .summary("微服务概述")
            .content("深入理解微服务分布式设计模式")
            .publishedAt(LocalDateTime.now())
            .build();

    Map<String, List<String>> highlightFields =
        Map.of("content", List.of("深入理解<em class=\"hl\">微服务</em>分布式"));

    SearchHit<DocSearchDocument> hit = mockHit("101", doc, highlightFields);
    SearchHits<DocSearchDocument> searchHits = mockSearchHits(1L, List.of(hit));

    when(elasticsearchOperations.search(any(NativeQuery.class), eq(DocSearchDocument.class)))
        .thenReturn(searchHits);

    DocumentSearchResponse response = searchQueryService.search(request);

    assertThat(response.items()).hasSize(1);
    assertThat(response.items().getFirst().hitSnippet())
        .isEqualTo("深入理解<em class=\"hl\">微服务</em>分布式");
  }

  @Test
  @DisplayName("无任何高亮命中时：摘要及文本降级截取")
  void testSearchSnippetFallbackToSummaryWhenNoHighlight() {
    DocumentSearchRequest request =
        new DocumentSearchRequest("无匹配高亮", 1L, null, null, 1, 20, DocSearchSortBy.RELEVANCE);

    DocSearchDocument doc =
        DocSearchDocument.builder()
            .id("102")
            .workspaceId("1")
            .title("默认标题")
            .summary("这是一份没有高亮命中的默认摘要文本")
            .content("正文")
            .publishedAt(LocalDateTime.now())
            .build();

    SearchHit<DocSearchDocument> hit = mockHit("102", doc, Map.of());
    SearchHits<DocSearchDocument> searchHits = mockSearchHits(1L, List.of(hit));

    when(elasticsearchOperations.search(any(NativeQuery.class), eq(DocSearchDocument.class)))
        .thenReturn(searchHits);

    DocumentSearchResponse response = searchQueryService.search(request);

    assertThat(response.items().getFirst().title()).isEqualTo("默认标题");
    assertThat(response.items().getFirst().summary()).isEqualTo("这是一份没有高亮命中的默认摘要文本");
    assertThat(response.items().getFirst().hitSnippet()).isEqualTo("这是一份没有高亮命中的默认摘要文本");
  }

  @Test
  @DisplayName("参数校验：关键词为空或工作区为空时抛出 BAD_REQUEST 业务异常")
  void testValidationFailure() {
    DocumentSearchRequest requestWithoutKeyword =
        new DocumentSearchRequest("", 1L, null, null, 1, 20, DocSearchSortBy.RELEVANCE);

    assertThatThrownBy(() -> searchQueryService.search(requestWithoutKeyword))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("BAD_REQUEST");

    DocumentSearchRequest requestWithoutWorkspace =
        new DocumentSearchRequest("Java", null, null, null, 1, 20, DocSearchSortBy.RELEVANCE);

    assertThatThrownBy(() -> searchQueryService.search(requestWithoutWorkspace))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("BAD_REQUEST");
  }

  @Test
  @DisplayName("ES 检索执行异常时：包装为 ES_SEARCH_FAILED 业务异常")
  void testEsSearchFailureWrapping() {
    DocumentSearchRequest request =
        new DocumentSearchRequest("Java", 1L, null, null, 1, 20, DocSearchSortBy.RELEVANCE);

    when(elasticsearchOperations.search(any(NativeQuery.class), eq(DocSearchDocument.class)))
        .thenThrow(new RuntimeException("Elasticsearch cluster connection timeout"));

    assertThatThrownBy(() -> searchQueryService.search(request))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("ES_SEARCH_FAILED");
  }
}
