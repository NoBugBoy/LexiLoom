package io.lexiloom.application.user.controller.admin;

import static org.hamcrest.Matchers.notNullValue;

import io.lexiloom.application.BaseControllerIntegrationTest;
import io.lexiloom.application.enums.AdminRoleEnum;
import io.lexiloom.application.user.model.request.AdminCreateRequest;
import io.lexiloom.application.user.service.AdminCommandService;
import io.lexiloom.application.user.service.AdminRoleService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AdminControllerTest extends BaseControllerIntegrationTest {

  @Autowired private AdminCommandService adminCommandService;
  @Autowired private AdminRoleService adminRoleService;
  @Autowired private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

  @Test
  @DisplayName("未认证访问管理员管理接口返回401")
  void testUnauthorized() {
    givenAnonymous().when().get("/admin").then().statusCode(401);
  }

  @Test
  @DisplayName("普通用户访问管理员管理接口返回403")
  void testForbiddenForRegularUser() {
    givenUser(999L).when().get("/admin").then().statusCode(403);
  }

  @Test
  @DisplayName("管理员分页查询管理员列表成功返回200")
  void testPageAdmins() {
    givenAdmin().when().get("/admin").then().statusCode(200).body("content", notNullValue());
  }

  @Test
  @DisplayName("管理员创建新管理员邀请成功返回200")
  void testCreateAdmin() {
    String email = "invite_" + UUID.randomUUID().toString().substring(0, 8) + "@test.com";
    AdminCreateRequest request = new AdminCreateRequest(email, "password123");

    givenAdmin().body(request).when().post("/admin/create").then().statusCode(200);
  }

  @Test
  @DisplayName("管理员修改自身密码成功")
  void testResetPassword() {
    String email = "reset_admin_" + UUID.randomUUID().toString().substring(0, 8) + "@test.com";
    String oldPassword = "oldPassword123";
    String newPassword = "newPassword456";

    Long adminId = adminCommandService.saveAdminUser(email, passwordEncoder.encode(oldPassword));
    adminRoleService.assignRole(adminId, AdminRoleEnum.ADMIN);

    AdminController.ResetPasswordRequest request =
        new AdminController.ResetPasswordRequest(oldPassword, newPassword);

    givenAdmin(adminId).body(request).when().post("/admin/reset/password").then().statusCode(200);
  }
}
