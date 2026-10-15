package io.lexiloom.application.user.api.dto;

import jakarta.validation.constraints.NotBlank;

public record Email(
    @jakarta.validation.constraints.Email(message = "邮箱格式错误") String email,
    @NotBlank(message = "邮箱验证码不能为空") String emailVerifiedCode,
    Boolean emailVerified) {}
