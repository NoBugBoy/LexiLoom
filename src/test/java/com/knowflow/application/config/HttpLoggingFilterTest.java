package com.knowflow.application.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ContentCachingResponseWrapper;

class HttpLoggingFilterTest {

  private HttpLoggingFilter filter;

  @BeforeEach
  void setUp() {
    filter = new HttpLoggingFilter();
  }

  @Test
  @DisplayName("验证普通同步 HTTP 请求使用 ContentCachingResponseWrapper 并正常回写响应体")
  void testOrdinaryRequestWrapsResponseAndCopiesBody() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/chat");
    request.setContentType(MediaType.APPLICATION_JSON_VALUE);
    request.setContent("{\"message\":\"hello\"}".getBytes(StandardCharsets.UTF_8));

    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<ServletResponse> responsePassedToChain = new AtomicReference<>();

    FilterChain chain =
        (req, resp) -> {
          responsePassedToChain.set(resp);
          resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
          resp.getWriter().write("{\"reply\":\"world\"}");
        };

    filter.doFilter(request, response, chain);

    // 验证传递给链的是包装器
    assertThat(responsePassedToChain.get())
        .isInstanceOf(ContentCachingResponseWrapper.class);

    // 验证 finally 成功执行 copyBodyToResponse，真实 response 能收到数据
    assertThat(response.getContentAsString()).isEqualTo("{\"reply\":\"world\"}");
  }

  @Test
  @DisplayName("验证 /stream 流式端点严禁被 ContentCachingResponseWrapper 包裹，直传原生 Response")
  void testStreamingEndpointDoesNotWrapResponse() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/chat/stream");
    request.setContentType(MediaType.APPLICATION_JSON_VALUE);
    request.setContent("{\"message\":\"stream question\"}".getBytes(StandardCharsets.UTF_8));

    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<ServletResponse> responsePassedToChain = new AtomicReference<>();

    FilterChain chain =
        (req, resp) -> {
          responsePassedToChain.set(resp);
          // 模拟 SSE 推流直接写原生流
          resp.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
          resp.getWriter().write("data: chunk1\n\n");
          resp.getWriter().flush();
        };

    filter.doFilter(request, response, chain);

    // 核心断言：流式端点决不能被 ContentCachingResponseWrapper 包裹！
    assertThat(responsePassedToChain.get())
        .isNotInstanceOf(ContentCachingResponseWrapper.class)
        .isSameAs(response);

    // 数据已实时直接刷新入真实 response
    assertThat(response.getContentAsString()).isEqualTo("data: chunk1\n\n");
  }

  @Test
  @DisplayName("验证请求头包含 Accept: text/event-stream 时同样禁止包装 Response")
  void testAcceptHeaderEventStreamDoesNotWrapResponse() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/notifications");
    request.addHeader(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);

    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<ServletResponse> responsePassedToChain = new AtomicReference<>();

    FilterChain chain =
        (req, resp) -> {
          responsePassedToChain.set(resp);
          resp.getWriter().write("data: ping\n\n");
        };

    filter.doFilter(request, response, chain);

    assertThat(responsePassedToChain.get())
        .isNotInstanceOf(ContentCachingResponseWrapper.class)
        .isSameAs(response);
    assertThat(response.getContentAsString()).isEqualTo("data: ping\n\n");
  }

  @Test
  @DisplayName("验证异步请求已启动时跳过 copyBodyToResponse 以免提前提交空响应")
  void testAsyncStartedSkipsCopyBody() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/other-async");
    request.setAsyncSupported(true);

    MockHttpServletResponse response = new MockHttpServletResponse();

    FilterChain chain =
        (req, resp) -> {
          // 模拟 Controller 开启异步（如 DeferredResult / Callable 等非预警路径）
          req.startAsync();
        };

    filter.doFilter(request, response, chain);

    // 响应没有被提前提交
    assertThat(response.isCommitted()).isFalse();
  }

  @Test
  @DisplayName("验证白名单路径 shouldNotFilter 判定正确")
  void testExcludePrefixes() {
    MockHttpServletRequest actuatorReq = new MockHttpServletRequest("GET", "/actuator/health");
    assertThat(filter.shouldNotFilter(actuatorReq)).isTrue();

    MockHttpServletRequest swaggerReq = new MockHttpServletRequest("GET", "/v3/api-docs/swagger-config");
    assertThat(filter.shouldNotFilter(swaggerReq)).isTrue();

    MockHttpServletRequest apiReq = new MockHttpServletRequest("GET", "/api/ai/chat");
    assertThat(filter.shouldNotFilter(apiReq)).isFalse();
  }
}
