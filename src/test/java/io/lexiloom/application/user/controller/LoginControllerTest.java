package io.lexiloom.application.user.controller;

import static org.hamcrest.Matchers.notNullValue;

import io.lexiloom.application.BaseControllerIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LoginControllerTest extends BaseControllerIntegrationTest {

  @Test
  @DisplayName("登录参数缺失返回400")
  void testLoginValidationFailure() {
    givenAnonymous()
        .body(Map.of("loginCode", ""))
        .when()
        .post("/auth/login")
        .then()
        .statusCode(400);
  }

  @Test
  @DisplayName("登录成功返回Token")
  void testLoginSuccess() {
    givenAnonymous()
        .body(Map.of("loginCode", "test_login_code_1001"))
        .when()
        .post("/auth/login")
        .then()
        .statusCode(200)
        .body("token", notNullValue());
  }
}
