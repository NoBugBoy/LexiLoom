package com.knowflow.application.user.usecase;

import cn.binarywang.wx.miniapp.api.WxMaService;
import com.knowflow.application.common.CustomerProperties;
import com.knowflow.application.common.UseCase;
import com.knowflow.application.common.security.RedisTokenManager;
import com.knowflow.application.exception.BusinessException;
import com.knowflow.application.user.api.UserQueryService;
import com.knowflow.application.user.model.entity.UserEntity;
import com.knowflow.application.user.service.UserCommandService;
import com.knowflow.application.user.service.impl.UserCommandServiceImpl.UserRegister;
import com.knowflow.application.user.usecase.LoginUseCase.LoginCommand;
import java.util.Collections;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.error.WxErrorException;
import org.springframework.stereotype.Service;

/**
 * @author yujian
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoginUseCase implements UseCase<LoginCommand, String> {
  private final UserCommandService userCommandService;
  private final UserQueryService userQueryService;
  private final WxMaService wxMaService;
  private final CustomerProperties customerProperties;
  private final RedisTokenManager redisTokenManager;

  public record LoginCommand(String loginCode) {}

  @Override
  public String execute(LoginCommand loginCommand) {
    final var loginCode = loginCommand.loginCode();
    String openid;
    String unionId;

    try {
      var sessionInfo = wxMaService.getUserService().getSessionInfo(loginCode);
      openid = sessionInfo.getOpenid();
      unionId = sessionInfo.getUnionid();

    } catch (WxErrorException e) {
      log.error("微信 code2Session 调用失败, loginCode: {}", loginCode, e);
      throw handleWxException(e, "登录异常,请稍后重试");
    }

    var user = userQueryService.isFirstLogin(openid);

    return doRegisterAndLogin(user, openid, unionId);
  }

  private String doRegisterAndLogin(Optional<UserEntity> user, String openid, String unionId) {
    var userId =
        user.orElseGet(
                () -> {
                  log.info("首次登录，开始注册 openId = {} ", openid);
                  return userCommandService.register(new UserRegister(openid, unionId, null, null));
                })
            .getId();
    log.info(">>> userId = {} 登录", userId);
    return redisTokenManager.generateToken(userId, Collections.emptyList());
  }

  private BusinessException handleWxException(WxErrorException e, String defaultMsg) {
    int errCode = e.getError().getErrorCode();
    return switch (errCode) {
      case 40029 -> new BusinessException("微信登录凭证已失效，请重新打开小程序");
      case 45011 -> new BusinessException("操作过于频繁，请稍后再试");
      case -1 -> new BusinessException("微信系统繁忙，请稍后重试");
      default -> new BusinessException(defaultMsg + " (错误码: " + errCode + ")");
    };
  }
}
