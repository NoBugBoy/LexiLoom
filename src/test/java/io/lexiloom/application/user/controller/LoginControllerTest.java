package io.lexiloom.application.user.controller;

import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.binarywang.wx.miniapp.api.WxMaUserService;
import cn.binarywang.wx.miniapp.bean.WxMaJscode2SessionResult;
import io.lexiloom.application.BaseControllerIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LoginControllerTest extends BaseControllerIntegrationTest {

  @Test
  @DisplayName("微信登录参数缺失返回400")
  void testLoginValidationFailure() {
    givenAnonymous()
        .body(Map.of("loginCode", ""))
        .when()
        .post("/auth/login")
        .then()
        .statusCode(400);
  }

  @Test
  @DisplayName("微信登录成功返回Token")
  void testLoginSuccess() throws Exception {
    WxMaUserService mockUserService = mock(WxMaUserService.class);
    when(wxMaService.getUserService()).thenReturn(mockUserService);

    WxMaJscode2SessionResult sessionResult = new WxMaJscode2SessionResult();
    sessionResult.setOpenid("mock_wx_openid_1001");
    when(mockUserService.getSessionInfo("wx_valid_code")).thenReturn(sessionResult);

    givenAnonymous()
        .body(Map.of("loginCode", "wx_valid_code"))
        .when()
        .post("/auth/login")
        .then()
        .statusCode(200)
        .body("token", notNullValue());
  }
}
