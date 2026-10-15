package io.lexiloom.application.user.controller.admin;

import io.lexiloom.application.user.api.AdminQueryService;
import io.lexiloom.application.user.model.request.AdminCreateRequest;
import io.lexiloom.application.user.model.request.AdminPageRequest;
import io.lexiloom.application.user.model.response.AdminItemResponse;
import io.lexiloom.application.user.service.AdminCommandService;
import io.lexiloom.application.user.usecase.admin.SendAdminRegisterEmailUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {
  private final SendAdminRegisterEmailUseCase sendAdminRegisterEmailUseCase;
  private final AdminCommandService adminCommandService;
  private final AdminQueryService adminQueryService;

  public record ResetPasswordRequest(String password, String newPassword) {}

  /**
   * 分页查询管理员列表，支持按邮箱模糊筛选。
   *
   * @param adminPageRequest 分页与筛选参数
   * @return 管理员分页结果
   */
  @GetMapping
  public Page<AdminItemResponse> page(AdminPageRequest adminPageRequest) {
    return adminQueryService.page(adminPageRequest);
  }

  /**
   * 创建管理员账号，并向对方邮箱发送注册邀请邮件。
   *
   * @param request 管理员创建参数
   */
  @PostMapping("/create")
  public void create(@Validated @RequestBody AdminCreateRequest request) {
    sendAdminRegisterEmailUseCase.execute(request);
  }

  /**
   * 校验旧密码后修改当前登录管理员的密码。
   *
   * @param request 含旧密码与新密码的重置参数
   */
  @PostMapping("/reset/password")
  public void resetPassword(@Validated @RequestBody ResetPasswordRequest request) {
    adminCommandService.resetPassword(request.password(), request.newPassword());
  }
}
