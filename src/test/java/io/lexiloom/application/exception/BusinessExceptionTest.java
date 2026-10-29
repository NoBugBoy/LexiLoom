package io.lexiloom.application.exception;

import static org.assertj.core.api.Assertions.assertThat;

import io.lexiloom.application.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class BusinessExceptionTest {

  @Test
  @DisplayName("测试各种构造函数和工厂方法")
  void testConstructorsAndFactories() {
    BusinessException e1 = new BusinessException(ErrorCode.Common.BAD_REQUEST);
    assertThat(e1.getCode()).isEqualTo("BAD_REQUEST");
    assertThat(e1.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);

    BusinessException e2 = new BusinessException(ErrorCode.Common.NOT_FOUND, "未找到数据");
    assertThat(e2.getMessage()).isEqualTo("未找到数据");

    BusinessException e3 = new BusinessException("错误消息");
    assertThat(e3.getCode()).isEqualTo("BIZ_ERROR");
    assertThat(e3.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);

    BusinessException e4 = BusinessException.notFound("nf").with("k", "v");
    assertThat(e4.getProperties()).containsEntry("k", "v");

    BusinessException e5 = BusinessException.unauthorized("unauth");
    assertThat(e5.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);

    BusinessException e6 = BusinessException.badRequest("bad");
    assertThat(e6.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
  }
}
