package io.lexiloom.application.user.controller;

import io.lexiloom.application.user.model.response.AccessToken;
import io.lexiloom.application.user.usecase.LoginUseCase;
import io.lexiloom.application.user.usecase.LoginUseCase.LoginCommand;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author yujian
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class LoginController {

  private final LoginUseCase loginUseCase;

  public record LoginRequest(@NotBlank(message = "loginCode 不能为空") String loginCode) {}

  /**
   * 用户凭据登录。
   *
   * @param request 登录请求，携带用户登录凭证 loginCode
   * @return 登录成功后的访问令牌
   */
  @PostMapping("/login")
  public AccessToken login(@Validated @RequestBody LoginRequest request) {
    return new AccessToken(loginUseCase.execute(new LoginCommand(request.loginCode)));
  }
}
