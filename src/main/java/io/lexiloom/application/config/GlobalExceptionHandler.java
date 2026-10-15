package io.lexiloom.application.config;

import io.lexiloom.application.exception.BusinessException;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final String TIMESTAMP = "timestamp";

  // ================= 1. 核心：处理自定义业务异常 BusinessException =================
  @ExceptionHandler(BusinessException.class)
  public ProblemDetail handleBusinessException(BusinessException ex) {
    log.warn("业务异常 [code={}]: {}", ex.getCode(), ex.getMessage());
    // 1. 创建基于异常内指定 HTTP Status 和文案的 ProblemDetail
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getHttpStatus(), ex.getMessage());

    problem.setTitle("业务处理失败");
    problem.setType(URI.create("urn:problem-type:biz-error"));

    // 2. 注入业务错误码和时间戳
    problem.setProperty("code", ex.getCode());
    problem.setProperty(TIMESTAMP, Instant.now());

    // 3. 将业务上下文的动态参数全部无缝塞入 ProblemDetail
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
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "请求参数校验失败，请检查输入");
    problem.setTitle("参数不合法");
    problem.setProperty("code", "PARAM_INVALID");
    problem.setProperty(TIMESTAMP, Instant.now());

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

  // ================= 3. 请求参数/请求体格式类异常（框架抛出，统一归一化为 4xx） =================
  @ExceptionHandler({
    MissingServletRequestParameterException.class,
    MissingServletRequestPartException.class,
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class
  })
  public ProblemDetail handleRequestParameterException(Exception ex) {
    log.warn("请求参数解析失败: {}", ex.getMessage());
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, resolveParameterErrorMessage(ex));
    problem.setTitle("请求参数不合法");
    problem.setProperty("code", "PARAM_INVALID");
    problem.setProperty(TIMESTAMP, Instant.now());
    return problem;
  }

  // ================= 4. 方法级参数校验异常 (类上 @Validated + @RequestParam 约束) =================
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
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "请求参数校验失败，请检查输入");
    problem.setTitle("参数不合法");
    problem.setProperty("code", "PARAM_INVALID");
    problem.setProperty(TIMESTAMP, Instant.now());
    problem.setProperty("invalidParams", invalidParams);
    return problem;
  }

  // ================= 5. 上传文件超出大小限制 =================
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ProblemDetail handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex) {
    log.warn("上传文件超过大小限制: {}", ex.getMessage());
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "上传文件超过大小限制");
    problem.setTitle("上传文件过大");
    problem.setProperty("code", "FILE_TOO_LARGE");
    problem.setProperty(TIMESTAMP, Instant.now());
    return problem;
  }

  // ================= 6. 兜底处理未捕获的系统异常 =================
  @ExceptionHandler(Exception.class)
  public ProblemDetail handleGeneralException(Exception ex) throws Exception {
    // 认证/授权异常必须继续上抛，交给 Spring Security 的 ExceptionTranslationFilter
    // 统一走 authenticationEntryPoint(401) / accessDeniedHandler(403)，
    // 否则会被这里的兜底逻辑截胡成 500。
    if (ex instanceof AuthenticationException || ex instanceof AccessDeniedException) {
      throw ex;
    }
    log.error("未捕获的系统全局异常: ", ex);
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "系统繁忙，请稍后再试");
    problem.setTitle("系统内部错误");
    problem.setProperty("code", "INTERNAL_SERVER_ERROR");
    problem.setProperty(TIMESTAMP, Instant.now());
    return problem;
  }

  private String resolveParameterErrorMessage(Exception ex) {
    if (ex instanceof MissingServletRequestParameterException missing) {
      return "缺少必填参数: " + missing.getParameterName();
    }
    if (ex instanceof MissingServletRequestPartException missingPart) {
      return "缺少必填请求部分: " + missingPart.getRequestPartName();
    }
    if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
      return "参数类型不正确: " + mismatch.getName();
    }
    return "请求体格式不正确，请检查 JSON 结构与字段类型";
  }
}
