package io.lexiloom.application.exception;

import io.lexiloom.application.exception.ErrorCode;
import io.lexiloom.application.exception.ErrorCode.Common;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class BusinessException extends RuntimeException {

  private final String code;
  private final HttpStatus httpStatus;
  private final Map<String, Object> properties = new HashMap<>(); // 👈 携带给 ProblemDetail 的额外上下文数据

  // 1. 基于 ErrorCode 构造
  public BusinessException(ErrorCode errorCode) {
    super(errorCode.getMessage());
    this.code = errorCode.getCode();
    this.httpStatus = errorCode.getHttpStatus();
  }

  // 2. 基于 ErrorCode 自定义文案
  public BusinessException(ErrorCode errorCode, String customMessage) {
    super(customMessage);
    this.code = errorCode.getCode();
    this.httpStatus = errorCode.getHttpStatus();
  }

  // 3. 极简快捷构造 (默认 HTTP 400)
  public BusinessException(String message) {
    super(message);
    this.code = "BIZ_ERROR";
    this.httpStatus = HttpStatus.BAD_REQUEST;
  }

  // 4. 👈 核心：链式追加业务上下文参数
  public BusinessException with(String key, Object value) {
    this.properties.put(key, value);
    return this;
  }

  // 5. 常用静态工厂方法 (开箱即用)
  public static BusinessException notFound(String message) {
    return new BusinessException(ErrorCode.Common.NOT_FOUND, message);
  }

  public static BusinessException badRequest(String message) {
    return new BusinessException(ErrorCode.Common.BAD_REQUEST, message);
  }

  public static BusinessException forbidden(String message) {
    return new BusinessException(ErrorCode.Common.FORBIDDEN, message);
  }

  public static BusinessException unauthorized(String message) {
    return new BusinessException(Common.UNAUTHORIZED, message);
  }
}
