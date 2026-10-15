package io.lexiloom.application.user.model.request;

import jakarta.validation.constraints.NotBlank;

public record ResetPasswordRequest(
    @NotBlank(message = "邮箱不能为空") String email, @NotBlank(message = "密码不能为空") String newPassword) {}
