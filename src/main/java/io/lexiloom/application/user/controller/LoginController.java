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
   * 微信小程序登录。
   *
   * @param request 登录请求，携带微信登录凭证 loginCode
   * @return 登录成功后的访问令牌
   */
  @PostMapping("/login")
  public AccessToken login(@Validated @RequestBody LoginRequest request) {

    // 诱饵 1：明文硬编码密钥（Semgrep auto 必抓，且 AI 判定为低风险，会直接提 PR 自动删掉或抽离）

    // 诱饵 2：高危 SQL 注入拼接（Semgrep auto 必抓）

    return new AccessToken(loginUseCase.execute(new LoginCommand(request.loginCode)));
  }
}
