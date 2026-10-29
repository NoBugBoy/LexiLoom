package io.lexiloom.application.config;

import io.lexiloom.application.exception.BusinessException;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final String TIMESTAMP = "timestamp";
  private static final String PARAM_INVALID_CODE = "PARAM_INVALID";

  // ================= 1. 核心：处理自定义业务异常 BusinessException =================
  @ExceptionHandler(BusinessException.class)
  public ProblemDetail handleBusinessException(BusinessException ex) {
    log.warn("业务异常 [code={}]: {}", ex.getCode(), ex.getMessage());
    ProblemDetail problem =
        buildProblemDetail(ex.getHttpStatus(), "业务处理失败", ex.getCode(), ex.getMessage());
    problem.setType(URI.create("urn:problem-type:biz-error"));

    // 将业务上下文的动态参数全部无缝塞入 ProblemDetail
    if (!ex.getProperties().isEmpty()) {
      ex.getProperties().forEach(problem::setProperty);
    }

    return problem;
  }

  // ================= 2. 处理 JSR-303 参数校验异常 (@Valid) =================
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleValidationException(MethodArgumentNotValidException ex) {
    log.warn("参数校验异常: {}", ex.getMessage());
    ProblemDetail problem =
        buildProblemDetail(
            HttpStatus.BAD_REQUEST, "参数不合法", PARAM_INVALID_CODE, "请求参数校验失败，请检查输入");

    Map<String, String> invalidParams =
        ex.getBindingResult().getFieldErrors().stream()
            .collect(
                Collectors.toMap(
                    FieldError::getField,
                    fieldError ->
                        fieldError.getDefaultMessage() != null
                            ? fieldError.getDefaultMessage()
                            : "格式错误",
                    (k1, k2) -> k1 // 重复字段保留第一个
                    ));
    problem.setProperty("invalidParams", invalidParams);

    return problem;
  }

  // ================= 3. 处理 Spring 7 方法级参数校验异常 (HandlerMethodValidationException) =================
  @ExceptionHandler(HandlerMethodValidationException.class)
  public ProblemDetail handleHandlerMethodValidationException(HandlerMethodValidationException ex) {
    log.warn("方法级参数校验异常: {}", ex.getMessage());
    Map<String, String> invalidParams = new LinkedHashMap<>();
    ex.getParameterValidationResults()
        .forEach(
            result -> {
              String paramName = result.getMethodParameter().getParameterName();
              String message =
                  result.getResolvableErrors().stream()
                      .map(MessageSourceResolvable::getDefaultMessage)
                      .filter(Objects::nonNull)
                      .findFirst()
                      .orElse("参数不合法");
              invalidParams.putIfAbsent(paramName != null ? paramName : "param", message);
            });

    ProblemDetail problem =
        buildProblemDetail(
            HttpStatus.BAD_REQUEST, "参数不合法", PARAM_INVALID_CODE, "请求参数校验失败，请检查输入");
    problem.setProperty("invalidParams", invalidParams);
    return problem;
  }

  // ================= 4. 请求参数/请求体格式类异常（框架抛出，统一归一化为 4xx） =================
  @ExceptionHandler({
    MissingServletRequestParameterException.class,
    MissingServletRequestPartException.class,
    MissingRequestHeaderException.class,
    MissingPathVariableException.class,
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class
  })
  public ProblemDetail handleRequestParameterException(Exception ex) {
    log.warn("请求参数解析失败: {}", ex.getMessage());
    return buildProblemDetail(
        HttpStatus.BAD_REQUEST,
        "请求参数不合法",
        PARAM_INVALID_CODE,
        resolveParameterErrorMessage(ex));
  }

  // ================= 5. 方法级参数校验异常 (类上 @Validated + @RequestParam 约束) =================
  @ExceptionHandler(ConstraintViolationException.class)
  public ProblemDetail handleConstraintViolationException(ConstraintViolationException ex) {
    log.warn("方法参数校验失败: {}", ex.getMessage());
    Map<String, String> invalidParams =
        ex.getConstraintViolations().stream()
            .collect(
                Collectors.toMap(
                    violation -> String.valueOf(violation.getPropertyPath()),
                    violation -> violation.getMessage() != null ? violation.getMessage() : "参数不合法",
                    (k1, k2) -> k1));
    ProblemDetail problem =
        buildProblemDetail(
            HttpStatus.BAD_REQUEST, "参数不合法", PARAM_INVALID_CODE, "请求参数校验失败，请检查输入");
    problem.setProperty("invalidParams", invalidParams);
    return problem;
  }

  // ================= 6. 上传文件超出大小限制 =================
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ProblemDetail handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex) {
    log.warn("上传文件超过大小限制: {}", ex.getMessage());
    return buildProblemDetail(
        HttpStatus.CONTENT_TOO_LARGE, "上传文件过大", "FILE_TOO_LARGE", "上传文件超过大小限制");
  }

  // ================= 7. 标准 HTTP 路由与协议异常 (404 / 405 / 406 / 415)，避免被兜底截胡为 500 =================
  @ExceptionHandler({
    NoResourceFoundException.class,
    HttpRequestMethodNotSupportedException.class,
    HttpMediaTypeNotSupportedException.class,
    HttpMediaTypeNotAcceptableException.class
  })
  public ProblemDetail handleStandardMvcException(Exception ex) throws Exception {
    if (ex instanceof ErrorResponse errorResponse) {
      HttpStatusCode status = errorResponse.getStatusCode();
      log.warn("HTTP 协议或路由异常 [status={}]: {}", status.value(), ex.getMessage());
      return buildProblemDetail(
          status, "请求无法处理", "HTTP_" + status.value(), ex.getMessage());
    }
    throw ex;
  }

  // ================= 8. 客户端提前断开连接 (如 SSE 流式取消)，静默忽略避免误报 500 =================
  @ExceptionHandler(AsyncRequestNotUsableException.class)
  public void handleAsyncRequestNotUsableException(AsyncRequestNotUsableException ex) {
    log.debug("客户端连接已提前关闭: {}", ex.getMessage());
  }

  // ================= 9. 兜底处理未捕获的系统异常 =================
  @ExceptionHandler(Exception.class)
  public ProblemDetail handleGeneralException(Exception ex) throws Exception {
    // 认证/授权异常必须继续上抛，交给 Spring Security 的 ExceptionTranslationFilter
    // 统一走 authenticationEntryPoint(401) / accessDeniedHandler(403)，
    // 否则会被这里的兜底逻辑截胡成 500。
    if (ex instanceof AuthenticationException || ex instanceof AccessDeniedException) {
      throw ex;
    }
    log.error("未捕获的系统全局异常: ", ex);
    return buildProblemDetail(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "系统内部错误",
        "INTERNAL_SERVER_ERROR",
        "系统繁忙，请稍后再试");
  }

  private ProblemDetail buildProblemDetail(
      HttpStatusCode status, String title, String code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    problem.setProperty("code", code);
    problem.setProperty(TIMESTAMP, Instant.now());
    return problem;
  }

  private String resolveParameterErrorMessage(Exception ex) {
    return switch (ex) {
      case MissingServletRequestParameterException missing ->
          "缺少必填参数: " + missing.getParameterName();
      case MissingServletRequestPartException missingPart ->
          "缺少必填请求部分: " + missingPart.getRequestPartName();
      case MissingRequestHeaderException missingHeader ->
          "缺少必填请求头: " + missingHeader.getHeaderName();
      case MissingPathVariableException missingPath ->
          "缺少必填路径变量: " + missingPath.getVariableName();
      case MethodArgumentTypeMismatchException mismatch ->
          "参数类型不正确: " + mismatch.getName();
      default -> "请求体格式不正确，请检查 JSON 结构与字段类型";
    };
  }
}
