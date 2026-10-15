package io.lexiloom.application.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * 全局 HTTP 请求与响应日志打印过滤器
 *
 * @author yujian
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpLoggingFilter extends OncePerRequestFilter {

  private static final int MAX_PAYLOAD_LENGTH = 10000;

  private static final Set<String> EXCLUDE_PREFIXES =
      Set.of("/actuator", "/v3/api-docs", "/swagger-ui", "/favicon.ico");

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return EXCLUDE_PREFIXES.stream().anyMatch(path::startsWith);
  }

  @Override
  @NullMarked
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    boolean isStreaming = isStreamingRequest(request);

    // 包装 Request 以支持重复读取 Payload
    ContentCachingRequestWrapper requestWrapper =
        request instanceof ContentCachingRequestWrapper req
            ? req
            : new ContentCachingRequestWrapper(request, MAX_PAYLOAD_LENGTH);

    // 重点：SSE / 异步流式响应严禁被 ContentCachingResponseWrapper 包裹！
    // 否则 doFilter 会在流开始前返回，finally 里 copyBodyToResponse() 会提前提交空响应体，
    // 后续 emitter 推送的数据只进内存缓存，前端永远收不到。
    ContentCachingResponseWrapper responseWrapper =
        isStreaming
            ? null
            : (response instanceof ContentCachingResponseWrapper resp
                ? resp
                : new ContentCachingResponseWrapper(response));

    HttpServletResponse responseToUse = responseWrapper != null ? responseWrapper : response;

    long startTime = System.currentTimeMillis();
    String uri = request.getRequestURI();
    String queryString = request.getQueryString();
    String fullUri = StringUtils.hasText(queryString) ? uri + "?" + queryString : uri;
    String method = request.getMethod();
    String clientIp = getClientIp(request);

    try {
      filterChain.doFilter(requestWrapper, responseToUse);
    } finally {
      long duration = System.currentTimeMillis() - startTime;
      int status = responseToUse.getStatus();

      String requestBody =
          getPayload(requestWrapper.getContentAsByteArray(), request.getContentType());

      if (responseWrapper == null || request.isAsyncStarted()) {
        log.info(
            """

            ====================== [HTTP LOG] ======================
            --> {} {} (IP: {})
            Request Body : {}
            <-- {} (Cost: {} ms) [STREAMING/ASYNC]
            Response Body: [STREAMING_RESPONSE_NOT_CACHED]
            =======================================================
            """,
            method,
            fullUri,
            clientIp,
            StringUtils.hasText(requestBody) ? requestBody : "[EMPTY]",
            status,
            duration);
      } else {
        String responseBody =
            getPayload(responseWrapper.getContentAsByteArray(), responseWrapper.getContentType());

        log.info(
            """

            ====================== [HTTP LOG] ======================
            --> {} {} (IP: {})
            Request Body : {}
            <-- {} (Cost: {} ms)
            Response Body: {}
            =======================================================
            """,
            method,
            fullUri,
            clientIp,
            StringUtils.hasText(requestBody) ? requestBody : "[EMPTY]",
            status,
            duration,
            StringUtils.hasText(responseBody) ? responseBody : "[EMPTY]");

        // 仅在非异步/非流式的普通响应中，将缓存的响应体内容回写至真实 Response
        responseWrapper.copyBodyToResponse();
      }
    }
  }

  private boolean isStreamingRequest(HttpServletRequest request) {
    if (request.getDispatcherType() == DispatcherType.ASYNC) {
      return true;
    }
    String uri = request.getRequestURI();
    if (uri != null && (uri.contains("/stream") || uri.endsWith(".sse"))) {
      return true;
    }
    String accept = request.getHeader(HttpHeaders.ACCEPT);
    return accept != null
        && (accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)
            || accept.contains("text/event-stream")
            || accept.contains("application/x-ndjson"));
  }

  private String getPayload(byte[] buf, String contentType) {
    if (buf == null || buf.length == 0) {
      return "";
    }
    // 仅打印文本/JSON/XML/表单类内容，避免二进制内容乱码
    if (contentType != null) {
      String lower = contentType.toLowerCase();
      boolean isTextOrJson =
          lower.contains("json")
              || lower.contains("xml")
              || lower.contains("text")
              || lower.contains("form");
      if (!isTextOrJson) {
        return "[Binary Data: " + buf.length + " bytes]";
      }
    }
    int length = Math.min(buf.length, MAX_PAYLOAD_LENGTH);
    String content = new String(buf, 0, length, StandardCharsets.UTF_8);
    return buf.length > MAX_PAYLOAD_LENGTH ? content + "... [TRUNCATED]" : content;
  }

  private String getClientIp(HttpServletRequest request) {
    String ip = request.getHeader("X-Forwarded-For");
    if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
      ip = request.getHeader("X-Real-IP");
    }
    if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
      ip = request.getRemoteAddr();
    }
    if (StringUtils.hasText(ip) && ip.contains(",")) {
      ip = ip.split(",")[0].trim();
    }
    return ip;
  }
}
