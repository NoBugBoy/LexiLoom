package io.lexiloom.application.user.service;

import jakarta.validation.constraints.NotBlank;

public interface AdminCommandService {
  /**
   * 保存管理员账号并返回其 ID。
   *
   * @param email 管理员邮箱
   * @param password 初始密码
   * @return 新管理员 ID
   */
  Long saveAdminUser(String email, String password);

  /**
   * 校验旧密码后修改当前登录管理员的密码；旧密码错误时抛出业务异常。
   *
   * @param password 旧密码
   * @param newPassword 新密码
   */
  void resetPassword(
      @NotBlank(message = "旧密码不能为空") String password,
      @NotBlank(message = "新密码不能为空") String newPassword);

  /**
   * 通过邮件一次性令牌重置密码；令牌无效或已失效时抛出业务异常。
   *
   * @param ott 重置邮件中的一次性令牌
   */
  void resetPassword(String ott);
}
