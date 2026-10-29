package io.lexiloom.application.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
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
      Set.of("/actuator", "/v3/api-docs", "/swagger-ui", "/favicon.ico", "/uploads");

  /** 匹配 JSON 中常见敏感凭据字段（兼容 camelCase 与 snake_case）并掩码脱敏 */
  private static final Pattern SENSITIVE_JSON_FIELD_PATTERN =
      Pattern.compile(
          "(\"(?:(?:old_?|new_?)?password|secret(?:_?key)?|(?:llm_?)?api_?key|(?:access_?|refresh_?|turnstile_?)?token)\"\\s*:\\s*\")([^\"]+)(\")",
          Pattern.CASE_INSENSITIVE);

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return path != null && EXCLUDE_PREFIXES.stream().anyMatch(path::startsWith);
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

    long startNanos = System.nanoTime();
    String uri = request.getRequestURI();
    String queryString = request.getQueryString();
    String fullUri = StringUtils.hasText(queryString) ? uri + "?" + queryString : uri;
    String method = request.getMethod();
    String clientIp = getClientIp(request);

    try {
      filterChain.doFilter(requestWrapper, responseToUse);
    } finally {
      long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
      boolean asyncOrStreaming = responseWrapper == null || request.isAsyncStarted();
      try {
        if (log.isInfoEnabled()) {
          int status = responseToUse.getStatus();
          String requestBody = extractRequestPayload(requestWrapper);

          if (asyncOrStreaming) {
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
            String responseBody = extractResponsePayload(responseWrapper);

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
          }
        }
      } finally {
        // 独立 finally 块确保即便日志拼装异常，非流式响应也必定回写至真实客户端连接
        if (!asyncOrStreaming) {
          responseWrapper.copyBodyToResponse();
        }
      }
    }
  }

  private boolean isStreamingRequest(HttpServletRequest request) {
    if (request.getDispatcherType() == DispatcherType.ASYNC) {
      return true;
    }
    String uri = request.getRequestURI();
    if (uri != null
        && (uri.contains("/stream")
            || uri.endsWith(".sse")
            || uri.endsWith("/chat/completions"))) {
      return true;
    }
    String accept = request.getHeader(HttpHeaders.ACCEPT);
    return accept != null
        && (accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)
            || accept.contains("text/event-stream")
            || accept.contains("application/x-ndjson"));
  }

  private String extractRequestPayload(ContentCachingRequestWrapper request) {
    String contentType = request.getContentType();
    if (isMultipartContentType(contentType)) {
      long size = request.getContentLengthLong();
      if (size < 0) {
        size = request.getContentAsByteArray().length;
      }
      return size > 0 ? "[Multipart Data: " + size + " bytes]" : "";
    }
    byte[] buf = request.getContentAsByteArray();
    if (buf.length == 0) {
      return "";
    }
    if (!isTextOrJsonContentType(contentType)) {
      return "[Binary Data: " + buf.length + " bytes]";
    }
    return formatTextPayload(buf);
  }

  private String extractResponsePayload(ContentCachingResponseWrapper response) {
    int size = response.getContentSize();
    if (size <= 0) {
      return "";
    }
    String contentType = response.getContentType();
    if (isMultipartContentType(contentType)) {
      return "[Multipart Data: " + size + " bytes]";
    }
    // 重要：先判断是否为二进制类型，避免对大体积文件下载调用 getContentAsByteArray() 触发堆内存完整拷贝
    if (!isTextOrJsonContentType(contentType)) {
      return "[Binary Data: " + size + " bytes]";
    }
    return formatTextPayload(response.getContentAsByteArray());
  }

  private boolean isMultipartContentType(@Nullable String contentType) {
    return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/");
  }

  private boolean isTextOrJsonContentType(@Nullable String contentType) {
    if (contentType == null) {
      return true;
    }
    String lower = contentType.toLowerCase(Locale.ROOT);
    return lower.contains("json")
        || lower.contains("xml")
        || lower.contains("text")
        || lower.contains("x-www-form-urlencoded");
  }

  private String formatTextPayload(byte[] buf) {
    int length = Math.min(buf.length, MAX_PAYLOAD_LENGTH);
    String content = maskSensitiveFields(new String(buf, 0, length, StandardCharsets.UTF_8));
    return buf.length > MAX_PAYLOAD_LENGTH ? content + "... [TRUNCATED]" : content;
  }

  private String maskSensitiveFields(String content) {
    if (!StringUtils.hasText(content) || !content.contains("\"")) {
      return content;
    }
    return SENSITIVE_JSON_FIELD_PATTERN.matcher(content).replaceAll("$1******$3");
  }

  private String getClientIp(HttpServletRequest request) {
    String ip = request.getHeader("X-Forwarded-For");
    if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
      ip = request.getHeader("X-Real-IP");
    }
    if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
      ip = request.getRemoteAddr();
    }
    if (StringUtils.hasText(ip)) {
      int commaIdx = ip.indexOf(',');
      if (commaIdx > 0) {
        ip = ip.substring(0, commaIdx).trim();
      }
    }
    return ip;
  }
}
