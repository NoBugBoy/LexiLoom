package io.lexiloom.application.user.controller;

import io.lexiloom.application.BaseControllerIntegrationTest;
import io.lexiloom.application.user.model.entity.UserEntity;
import io.lexiloom.application.user.service.UserCommandService;
import io.lexiloom.application.user.service.impl.UserCommandServiceImpl.UserRegister;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class UserControllerTest extends BaseControllerIntegrationTest {

  @Autowired private UserCommandService userCommandService;

  @Test
  @DisplayName("未认证访问个人中心接口返回401")
  void testUnauthorized() {
    UserController.UpdateProfileRequest request =
        new UserController.UpdateProfileRequest("NewNick", "https://img.com/a.png");

    givenAnonymous().body(request).when().put("/user/update/profile").then().statusCode(401);
  }

  @Test
  @DisplayName("登录用户修改个人资料成功返回204")
  void testUpdateProfileSuccess() {
    String openId = "openid_" + UUID.randomUUID().toString().substring(0, 8);
    UserEntity user =
        userCommandService.register(new UserRegister(openId, "unionid_" + openId, null, null));
    Long userId = user.getId();

    UserController.UpdateProfileRequest request =
        new UserController.UpdateProfileRequest("MyNickName", "https://example.com/avatar.png");

    givenUser(userId).body(request).when().put("/user/update/profile").then().statusCode(204);
  }

  @Test
  @DisplayName("登录用户修改个人资料入参非法返回400")
  void testUpdateProfileValidationFailure() {
    UserController.UpdateProfileRequest request = new UserController.UpdateProfileRequest("", "");

    givenUser(1L).body(request).when().put("/user/update/profile").then().statusCode(400);
  }
}
