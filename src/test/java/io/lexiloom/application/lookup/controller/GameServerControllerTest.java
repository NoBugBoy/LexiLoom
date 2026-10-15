package io.lexiloom.application.lookup.controller;

import static org.hamcrest.Matchers.notNullValue;

import io.lexiloom.application.BaseControllerIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GameServerControllerTest extends BaseControllerIntegrationTest {

  @Test
  @DisplayName("未认证访问游戏服务器选项接口返回401")
  void testOptionsUnauthorized() {
    givenAnonymous().when().get("/server").then().statusCode(401);
  }

  @Test
  @DisplayName("登录用户访问游戏服务器选项接口成功返回200")
  void testOptionsSuccess() {
    givenUser(1L).when().get("/server").then().statusCode(200).body(notNullValue());
  }
}
