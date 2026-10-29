package io.lexiloom.application.user.usecase;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.common.UseCase;
import io.lexiloom.application.common.security.RedisTokenManager;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.model.entity.UserEntity;
import io.lexiloom.application.user.service.UserCommandService;
import io.lexiloom.application.user.service.UserEntityQueryService;
import io.lexiloom.application.user.service.impl.UserCommandServiceImpl.UserRegister;
import io.lexiloom.application.user.usecase.LoginUseCase.LoginCommand;
import java.util.Collections;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 用户凭据登录用例
 *
 * @author yujian
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoginUseCase implements UseCase<LoginCommand, String> {
  private final UserCommandService userCommandService;
  private final UserEntityQueryService userQueryService;
  private final RedisTokenManager redisTokenManager;

  public record LoginCommand(String loginCode) {}

  @Override
  public String execute(LoginCommand loginCommand) {
    final var loginCode = loginCommand.loginCode();
    if (StrUtil.isBlank(loginCode)) {
      throw BusinessException.badRequest("登录凭据不能为空");
    }

    var user = userQueryService.isFirstLogin(loginCode);
    return doRegisterAndLogin(user, loginCode, null);
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
}
