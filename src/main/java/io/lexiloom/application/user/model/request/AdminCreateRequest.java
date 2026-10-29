package io.lexiloom.application.user.model.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.Length;

public record AdminCreateRequest(
    @NotBlank(message = "邮箱不能为空") @Email(message = "邮箱格式不正确") String email,
    @NotBlank(message = "密码不能为空") @Length(min = 6, max = 20, message = "密码长度必须在6-20位之间") String password) {}
