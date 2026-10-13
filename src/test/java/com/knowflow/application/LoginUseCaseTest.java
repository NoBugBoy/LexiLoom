package com.knowflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.binarywang.wx.miniapp.api.WxMaUserService;
import cn.binarywang.wx.miniapp.bean.WxMaJscode2SessionResult;
import cn.binarywang.wx.miniapp.bean.WxMaPhoneNumberInfo;
import com.knowflow.application.common.CustomerProperties;
import com.knowflow.application.common.security.RedisTokenManager;
import com.knowflow.application.exception.BusinessException;
import com.knowflow.application.user.api.UserQueryService;
import com.knowflow.application.user.model.entity.UserEntity;
import com.knowflow.application.user.service.UserCommandService;
import com.knowflow.application.user.usecase.LoginUseCase;
import com.knowflow.application.user.usecase.LoginUseCase.LoginCommand;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Optional;
import me.chanjar.weixin.common.error.WxError;
import me.chanjar.weixin.common.error.WxErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoginUseCaseTest {

  private static final KeyPair KEY_PAIR = rsaKeyPair();

  @Mock private WxMaService wxMaService;

  @Mock private WxMaUserService wxMaUserService;

  @Mock private UserQueryService userQueryService;

  @Mock private UserCommandService userCommandService;

  @Mock private CustomerProperties customerProperties;

  @Mock private CustomerProperties.Security security;

  @Mock private RedisTokenManager redisTokenManager;

  @InjectMocks private LoginUseCase loginUseCase;

  @BeforeEach
  void wireNestedMocks() {
    // 每个测试都要用到的“接线”，用 lenient 避免严格模式报多余 stubbing
    lenient().when(wxMaService.getUserService()).thenReturn(wxMaUserService);
    lenient().when(customerProperties.security()).thenReturn(security);
    lenient().when(security.getPrivateKey()).thenReturn(KEY_PAIR.getPrivate());
    lenient().when(redisTokenManager.generateToken(any(), any())).thenReturn("mock-token-uuid");
  }

  @Test
  @DisplayName("首次登录：调用 register 注册并返回 token")
  void shouldRegisterOnFirstLogin() throws WxErrorException {
    var session = new WxMaJscode2SessionResult();
    session.setOpenid("openid-1");
    session.setUnionid("unionid-1");

    var phone = new WxMaPhoneNumberInfo();
    phone.setPhoneNumber("13800138000");
    phone.setCountryCode("86");

    var newUser = new UserEntity();
    newUser.setId(100L);

    when(wxMaUserService.getSessionInfo("login_code")).thenReturn(session);
    when(userQueryService.isFirstLogin("openid-1")).thenReturn(Optional.empty());
    when(userCommandService.register(any())).thenReturn(newUser);

    var token = loginUseCase.execute(new LoginCommand("login_code"));

    assertThat(token).as("accessToken").isNotBlank();
    verify(userCommandService).register(any());
  }

  @Test
  @DisplayName("老用户：不注册、不查手机号，直接返回 token")
  void shouldLoginExistingUserWithoutRegistering() throws WxErrorException {
    var session = new WxMaJscode2SessionResult();
    session.setOpenid("openid-1");
    session.setUnionid("unionid-1");

    var user = new UserEntity();
    user.setId(42L);

    when(wxMaUserService.getSessionInfo("login_code")).thenReturn(session);
    when(userQueryService.isFirstLogin("openid-1")).thenReturn(Optional.of(user));

    var token = loginUseCase.execute(new LoginCommand("login_code"));

    assertThat(token).isNotBlank();
    verify(userCommandService, never()).register(any());
    verify(wxMaUserService, never()).getPhoneNoInfo(anyString());
  }

  @Test
  @DisplayName("微信 code 无效：映射为 BusinessException")
  void shouldMapWxInvalidCodeToBusinessException() throws WxErrorException {
    when(wxMaUserService.getSessionInfo("bad_code"))
        .thenThrow(new WxErrorException(new WxError(40029, "invalid code")));

    var badCommand = new LoginCommand("bad_code");
    assertThatThrownBy(() -> loginUseCase.execute(badCommand))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("凭证已失效");

    verify(userCommandService, never()).register(any());
  }

  private static KeyPair rsaKeyPair() {
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException("生成 RSA 密钥失败", e);
    }
  }
}
