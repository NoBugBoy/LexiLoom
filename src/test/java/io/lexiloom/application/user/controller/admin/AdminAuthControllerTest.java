package io.lexiloom.application.user.controller.admin;

import static org.hamcrest.Matchers.notNullValue;

import io.lexiloom.application.BaseControllerIntegrationTest;
import io.lexiloom.application.enums.AdminRoleEnum;
import io.lexiloom.application.user.model.request.AdminLoginRequest;
import io.lexiloom.application.user.service.AdminCommandService;
import io.lexiloom.application.user.service.AdminRoleService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AdminAuthControllerTest extends BaseControllerIntegrationTest {

  @Autowired private AdminCommandService adminCommandService;
  @Autowired private AdminRoleService adminRoleService;
  @Autowired private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

  @Test
  @DisplayName("管理员登录失败 - 用户名或密码错误返回400")
  void testLoginFailure() {
    AdminLoginRequest request =
        new AdminLoginRequest("nonexistent@example.com", "wrongpassword", null, null);

    givenAnonymous().body(request).when().post("/admin/auth/login").then().statusCode(400);
  }

  @Test
  @DisplayName("管理员登录成功 - 返回Token")
  void testLoginSuccess() {
    String email = "admin_" + UUID.randomUUID().toString().substring(0, 8) + "@test.com";
    String password = "adminPassword123";

    Long adminId = adminCommandService.saveAdminUser(email, passwordEncoder.encode(password));
    adminRoleService.assignRole(adminId, AdminRoleEnum.ADMIN);

    AdminLoginRequest request = new AdminLoginRequest(email, password, null, null);

    givenAnonymous()
        .body(request)
        .when()
        .post("/admin/auth/login")
        .then()
        .statusCode(200)
        .body("token", notNullValue());
  }

  @Test
  @DisplayName("发送重置密码邮件参数校验失败返回400")
  void testResetPasswordSendValidationFailure() {
    givenAnonymous()
        .body(Map.of("email", "", "newPassword", ""))
        .when()
        .post("/admin/auth/reset-password-send")
        .then()
        .statusCode(400);
  }

  @Test
  @DisplayName("使用无效OTT完成注册返回400")
  void testCompleteRegisterInvalidOtt() {
    givenAnonymous()
        .queryParam("ott", "invalid-ott-token")
        .when()
        .get("/admin/auth/complete-register")
        .then()
        .statusCode(400);
  }
}
