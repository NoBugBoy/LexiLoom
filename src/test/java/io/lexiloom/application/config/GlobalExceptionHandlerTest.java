package io.lexiloom.application.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lexiloom.application.common.security.TurnstileProperties;
import io.lexiloom.application.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  @DisplayName("业务异常转换为携带业务码与动态属性的 ProblemDetail")
  void testHandleBusinessException() {
    BusinessException ex =
        BusinessException.badRequest("参数错误").with("field", "workspaceId");
    ProblemDetail problem = handler.handleBusinessException(ex);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(problem.getDetail()).isEqualTo("参数错误");
    assertThat(problem.getProperties()).containsEntry("field", "workspaceId");
  }

  @Test
  @DisplayName("请求参数异常通过 switch 模式匹配返回精确错误文案")
  void testHandleRequestParameterException() {
    MissingServletRequestParameterException missingParam =
        new MissingServletRequestParameterException("pageNum", "int");
    ProblemDetail p1 = handler.handleRequestParameterException(missingParam);
    assertThat(p1.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(p1.getDetail()).isEqualTo("缺少必填参数: pageNum");

    MissingServletRequestPartException missingPart = new MissingServletRequestPartException("file");
    ProblemDetail p2 = handler.handleRequestParameterException(missingPart);
    assertThat(p2.getDetail()).isEqualTo("缺少必填请求部分: file");
  }

  @Test
  @DisplayName("标准 404/405 MVC 异常返回对应 4xx 状态码而非 500")
  void testHandleStandardMvcException() throws Exception {
    NoResourceFoundException notFound =
        new NoResourceFoundException(HttpMethod.GET, "api/unknown", "api/unknown");
    ProblemDetail p404 = handler.handleStandardMvcException(notFound);
    assertThat(p404.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    assertThat(p404.getProperties()).containsEntry("code", "HTTP_404");

    HttpRequestMethodNotSupportedException methodNotSupported =
        new HttpRequestMethodNotSupportedException("DELETE");
    ProblemDetail p405 = handler.handleStandardMvcException(methodNotSupported);
    assertThat(p405.getStatus()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
    assertThat(p405.getProperties()).containsEntry("code", "HTTP_405");
  }

  @Test
  @DisplayName("安全授权异常继续上抛交由 Spring Security 处理")
  void testSecurityExceptionRethrown() {
    AccessDeniedException accessDenied = new AccessDeniedException("Forbidden");
    assertThatThrownBy(() -> handler.handleGeneralException(accessDenied))
        .isSameAs(accessDenied);
  }

  @Test
  @DisplayName("验证 TurnstileProperties 默认值回退与密钥脱敏")
  void testTurnstileProperties() {
    TurnstileProperties props = new TurnstileProperties(true, "  my-secret-key  ", " ");
    assertThat(props.secretKey()).isEqualTo("my-secret-key");
    assertThat(props.siteverifyUrl())
        .isEqualTo("https://challenges.cloudflare.com/turnstile/v0/siteverify");
    assertThat(props.toString()).doesNotContain("my-secret-key").contains("secretKey=******");
  }
}
