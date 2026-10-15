package io.lexiloom.application.document.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.document.search.dto.DocSearchItemVO;
import io.lexiloom.application.document.search.dto.DocSearchSortBy;
import io.lexiloom.application.document.search.dto.DocumentSearchRequest;
import io.lexiloom.application.document.search.dto.DocumentSearchResponse;
import io.lexiloom.application.document.service.DocumentSearchQueryService;
import io.lexiloom.application.document.service.SearchHistoryService;
import io.lexiloom.application.document.service.SearchSuggestQueryService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentSearchController 单元测试")
class DocumentSearchControllerTest {

  private static final Long TEST_USER_ID = 1001L;

  private MockMvc mockMvc;

  @Mock private DocumentSearchQueryService documentSearchQueryService;
  @Mock private SearchSuggestQueryService searchSuggestQueryService;
  @Mock private SearchHistoryService searchHistoryService;

  @InjectMocks private DocumentSearchController documentSearchController;

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        MockMvcBuilders.standaloneSetup(documentSearchController).setValidator(validator).build();

    SecurityHolder.setAuthentication(TEST_USER_ID);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("GET /api/search - 全文搜索成功返回高亮分页结果")
  void testSearch_Success() throws Exception {
    // Arrange
    DocSearchItemVO item =
        new DocSearchItemVO(
            "doc-123",
            "<em class=\"hl\">DDD</em> 领域驱动设计",
            "<em class=\"hl\">DDD</em> 核心理念",
            "聚合根与防腐层是 <em class=\"hl\">DDD</em> 的基石",
            "10",
            List.of("java", "ddd"),
            "IMPORT",
            LocalDateTime.now());
    DocumentSearchResponse response = DocumentSearchResponse.of(1, 20, List.of(item));

    when(documentSearchQueryService.search(any(DocumentSearchRequest.class))).thenReturn(response);

    // Act & Assert
    mockMvc
        .perform(
            get("/api/search")
                .param("keyword", "DDD")
                .param("workspaceId", "1")
                .param("pageNum", "1")
                .param("pageSize", "20")
                .param("sortBy", "RELEVANCE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total", is(1)))
        .andExpect(jsonPath("$.pages", is(1)))
        .andExpect(jsonPath("$.items", hasSize(1)))
        .andExpect(jsonPath("$.items[0].id", is("doc-123")))
        .andExpect(jsonPath("$.items[0].title", is("<em class=\"hl\">DDD</em> 领域驱动设计")))
        .andExpect(jsonPath("$.items[0].hitSnippet", is("聚合根与防腐层是 <em class=\"hl\">DDD</em> 的基石")));

    ArgumentCaptor<DocumentSearchRequest> captor =
        ArgumentCaptor.forClass(DocumentSearchRequest.class);
    verify(documentSearchQueryService).search(captor.capture());
    DocumentSearchRequest capturedRequest = captor.getValue();
    org.assertj.core.api.Assertions.assertThat(capturedRequest.keyword()).isEqualTo("DDD");
    org.assertj.core.api.Assertions.assertThat(capturedRequest.workspaceId()).isEqualTo(1L);
    org.assertj.core.api.Assertions.assertThat(capturedRequest.sortBy())
        .isEqualTo(DocSearchSortBy.RELEVANCE);
  }

  @Test
  @DisplayName("GET /api/search - 缺少必填参数 workspaceId 返回 400")
  void testSearch_MissingWorkspaceId_Returns400() throws Exception {
    mockMvc.perform(get("/api/search").param("keyword", "DDD")).andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("GET /api/search - 缺少必填参数 keyword 返回 400")
  void testSearch_BlankKeyword_Returns400() throws Exception {
    mockMvc
        .perform(get("/api/search").param("keyword", "").param("workspaceId", "1"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("GET /api/search/suggest - 搜索建议补全成功返回词条列表")
  void testSuggest_Success() throws Exception {
    when(searchSuggestQueryService.suggest(1L, "微服务"))
        .thenReturn(List.of("微服务架构设计", "微服务拆分原则", "微服务治理实践"));

    mockMvc
        .perform(get("/api/search/suggest").param("workspaceId", "1").param("keyword", "微服务"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(3)))
        .andExpect(jsonPath("$[0]", is("微服务架构设计")))
        .andExpect(jsonPath("$[1]", is("微服务拆分原则")))
        .andExpect(jsonPath("$[2]", is("微服务治理实践")));

    verify(searchSuggestQueryService).suggest(1L, "微服务");
  }

  @Test
  @DisplayName("GET /api/search/suggest - 缺少必填参数返回 400")
  void testSuggest_MissingParams_Returns400() throws Exception {
    mockMvc
        .perform(get("/api/search/suggest").param("keyword", "微服务"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(get("/api/search/suggest").param("workspaceId", "1"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("GET /api/search/history - 获取用户搜索历史成功并传递 workspaceId")
  void testGetHistory_Success() throws Exception {
    when(searchHistoryService.getHistory(TEST_USER_ID, 1L, 10))
        .thenReturn(List.of("Java 25", "Spring Boot 4", "Elasticsearch"));

    mockMvc
        .perform(get("/api/search/history").param("workspaceId", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(3)))
        .andExpect(jsonPath("$[0]", is("Java 25")))
        .andExpect(jsonPath("$[1]", is("Spring Boot 4")))
        .andExpect(jsonPath("$[2]", is("Elasticsearch")));

    verify(searchHistoryService).getHistory(TEST_USER_ID, 1L, 10);
  }

  @Test
  @DisplayName("GET /api/search/history - 自定义 size 参数生效")
  void testGetHistory_CustomSize() throws Exception {
    when(searchHistoryService.getHistory(TEST_USER_ID, null, 5))
        .thenReturn(List.of("Java 25", "Spring Boot 4"));

    mockMvc
        .perform(get("/api/search/history").param("size", "5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(2)));

    verify(searchHistoryService).getHistory(TEST_USER_ID, null, 5);
  }

  @Test
  @DisplayName("DELETE /api/search/history - QueryParam 删除单条搜索历史（支持特殊字符如 C++）")
  void testDeleteHistory_QueryParam_Success() throws Exception {
    mockMvc
        .perform(
            delete("/api/search/history").param("workspaceId", "1").param("keyword", "C++ & CI/CD"))
        .andExpect(status().isOk());

    verify(searchHistoryService).delete(TEST_USER_ID, 1L, "C++ & CI/CD");
  }

  @Test
  @DisplayName("DELETE /api/search/history/{keyword} - 路径参数删除单条搜索历史兼容性")
  void testDeleteHistoryItem_Success() throws Exception {
    mockMvc
        .perform(delete("/api/search/history/{keyword}", "Elasticsearch").param("workspaceId", "1"))
        .andExpect(status().isOk());

    verify(searchHistoryService).delete(TEST_USER_ID, 1L, "Elasticsearch");
  }

  @Test
  @DisplayName("DELETE /api/search/history - 清空当前用户工作区搜索历史成功")
  void testClearHistory_Success() throws Exception {
    mockMvc
        .perform(delete("/api/search/history").param("workspaceId", "1"))
        .andExpect(status().isOk());

    verify(searchHistoryService).clear(TEST_USER_ID, 1L);
  }
}
