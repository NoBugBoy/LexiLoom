package io.lexiloom.application.ai.controller.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.lexiloom.application.ai.model.request.OpenApiKnowledgeSearchRequest;
import io.lexiloom.application.ai.model.vo.McpDocumentDetailVO;
import io.lexiloom.application.ai.model.vo.McpSearchResultVO;
import io.lexiloom.application.ai.model.vo.McpWorkspaceVO;
import io.lexiloom.application.ai.service.OpenApiQueryService;
import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.common.security.OpenApiPrincipal;
import io.lexiloom.application.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class OpenApiControllerTest {

  @Mock private OpenApiQueryService openApiQueryService;

  private OpenApiController controller;

  @BeforeEach
  void setUp() {
    controller = new OpenApiController(openApiQueryService);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("listWorkspaces - 成功返回工作空间列表")
  void testListWorkspaces() {
    McpWorkspaceVO vo = new McpWorkspaceVO(1L, "公共空间", "简介", 10L, 100L);
    when(openApiQueryService.listWorkspaces(true)).thenReturn(List.of(vo));

    List<McpWorkspaceVO> result = controller.listWorkspaces();
    assertThat(result).hasSize(1);
    assertThat(result.getFirst().name()).isEqualTo("公共空间");
  }

  @Test
  @DisplayName("getApiDocumentation - 返回 Markdown 接口文档")
  void testGetApiDocumentation() throws Exception {
    var response = controller.getApiDocumentation();

    assertThat(response.getHeaders().getContentType().toString())
        .isEqualTo("text/markdown;charset=UTF-8");
    assertThat(response.getBody()).contains("# LexiLoom OpenAPI 接口文档");
    assertThat(response.getBody()).contains("/knowledge/search");
  }

  @Test
  @DisplayName("searchPost - 成功检索召回切片 (未传来源时默认归一化为 OPENAPI)")
  void testSearchPost_success() {
    OpenApiKnowledgeSearchRequest req = new OpenApiKnowledgeSearchRequest("架构", 1L, 5, 0.6);
    McpSearchResultVO vo = new McpSearchResultVO("架构", 1L, 0, List.of(), "trace-1");
    when(openApiQueryService.search("架构", 1L, 5, 0.6, "OPENAPI", true)).thenReturn(vo);

    McpSearchResultVO result = controller.searchPost(null, req);
    assertThat(result).isNotNull();
    assertThat(result.traceId()).isEqualTo("trace-1");
  }

  @Test
  @DisplayName("searchGet - 成功检索 (传递受管 Header X-Call-Source: CLI_SKILL)")
  void testSearchGet_success() {
    McpSearchResultVO vo = new McpSearchResultVO("架构", 1L, 0, List.of(), "trace-2");
    when(openApiQueryService.search("架构", 1L, 5, 0.5, "CLI_SKILL", true)).thenReturn(vo);

    McpSearchResultVO result = controller.searchGet("CLI_SKILL", null, "架构", 1L, 5, 0.5);
    assertThat(result.traceId()).isEqualTo("trace-2");
  }

  @Test
  @DisplayName("searchGet - 传入非法自定义来源时严格归一化拦截回退为 OPENAPI")
  void testSearchGet_customDisallowedNormalized() {
    McpSearchResultVO vo = new McpSearchResultVO("架构", 1L, 0, List.of(), "trace-3");
    when(openApiQueryService.search("架构", 1L, 5, 0.5, "OPENAPI", true)).thenReturn(vo);

    McpSearchResultVO result =
        controller.searchGet("INVALID_CUSTOM_SOURCE", null, "架构", 1L, 5, 0.5);
    assertThat(result.traceId()).isEqualTo("trace-3");
  }

  @Test
  @DisplayName("getDocumentDetail - 成功获取正文")
  void testGetDocumentDetail_success() {
    LocalDateTime now = LocalDateTime.now();
    McpDocumentDetailVO detail =
        new McpDocumentDetailVO(
            10L, "设计规范", 1L, "技术", "APPROVED", 1, "# 内容", now, now);
    when(openApiQueryService.getDocumentDetail(10L, true)).thenReturn(detail);

    McpDocumentDetailVO result = controller.getDocumentDetail(10L);
    assertThat(result.id()).isEqualTo(10L);
    assertThat(result.title()).isEqualTo("设计规范");
  }

  @Test
  @DisplayName("chatCompletions - 同步调用成功返回回复")
  void testChatCompletions_sync() {
    io.lexiloom.application.ai.model.request.OpenApiChatCompletionRequest req =
        new io.lexiloom.application.ai.model.request.OpenApiChatCompletionRequest(
            "测试问题", 1L, null, false, false, null, null);
    io.lexiloom.application.ai.model.response.AiChatResponse resp =
        new io.lexiloom.application.ai.model.response.AiChatResponse(
            "测试回答", null, List.of(), 100L, "trace-10");
    when(openApiQueryService.chat(req)).thenReturn(resp);

    Object result = controller.chatCompletions(req);
    assertThat(result).isInstanceOf(io.lexiloom.application.ai.model.response.AiChatResponse.class);
    assertThat(((io.lexiloom.application.ai.model.response.AiChatResponse) result).content()).isEqualTo("测试回答");
  }

  @Test
  @DisplayName("chatCompletions - 流式调用返回 SseEmitter")
  void testChatCompletions_stream() {
    io.lexiloom.application.ai.model.request.OpenApiChatCompletionRequest req =
        new io.lexiloom.application.ai.model.request.OpenApiChatCompletionRequest(
            "测试问题", 1L, null, true, false, null, null);
    org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
        new org.springframework.web.servlet.mvc.method.annotation.SseEmitter();
    when(openApiQueryService.streamChat(req)).thenReturn(emitter);

    Object result = controller.chatCompletions(req);
    assertThat(result).isSameAs(emitter);
  }
}
