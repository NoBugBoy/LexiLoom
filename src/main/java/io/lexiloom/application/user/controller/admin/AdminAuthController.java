package io.lexiloom.application.user.controller.admin;

import io.lexiloom.application.user.model.request.AdminLoginRequest;
import io.lexiloom.application.user.model.request.ResetPasswordRequest;
import io.lexiloom.application.user.model.response.AccessToken;
import io.lexiloom.application.user.service.AdminCommandService;
import io.lexiloom.application.user.usecase.admin.AdminLoginUseCase;
import io.lexiloom.application.user.usecase.admin.AdminRegisterUseCase;
import io.lexiloom.application.user.usecase.admin.SendAdminRestPasswordEmailUseCase;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

  private final AdminRegisterUseCase adminRegisterUseCase;
  private final AdminLoginUseCase adminLoginUseCase;
  private final AdminCommandService adminCommandService;
  private final SendAdminRestPasswordEmailUseCase sendAdminRestPasswordEmailUseCase;

  /**
   * 通过邮箱一次性令牌完成管理员注册激活。
   *
   * @param ott 注册邮件中的一次性令牌
   * @return 激活成功后的访问令牌
   */
  @GetMapping("/complete-register")
  public AccessToken completeRegister(
      @RequestParam("ott") @NotBlank(message = "令牌不能为空") String ott) {
    return new AccessToken(adminRegisterUseCase.execute(ott));
  }

  /**
   * 通过邮箱一次性令牌完成管理员密码重置。
   *
   * @param ott 重置邮件中的一次性令牌
   * @return 202 Accepted
   */
  @GetMapping("/complete-reset")
  public ResponseEntity<Void> completeReset(
      @RequestParam("ott") @NotBlank(message = "令牌不能为空") String ott) {
    adminCommandService.resetPassword(ott);
    return ResponseEntity.status(HttpStatus.ACCEPTED).build();
  }

  /**
   * 管理员账号密码登录。
   *
   * @param request 管理员登录参数
   * @return 登录成功后的访问令牌
   */
  @PostMapping("/login")
  public AccessToken login(@Validated @RequestBody AdminLoginRequest request) {
    return new AccessToken(adminLoginUseCase.execute(request));
  }

  /**
   * 发送管理员重置密码邮件。
   *
   * @param resetPasswordSender 重置密码请求参数
   * @return 202 Accepted
   */
  @PostMapping("/reset-password-send")
  public ResponseEntity<Void> resetPasswordSend(
      @Validated @RequestBody ResetPasswordRequest resetPasswordSender) {
    sendAdminRestPasswordEmailUseCase.execute(resetPasswordSender);
    return ResponseEntity.status(HttpStatus.ACCEPTED).build();
  }
}
