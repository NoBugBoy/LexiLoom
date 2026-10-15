package io.lexiloom.application.user.model.request;

import org.hibernate.validator.constraints.Length;

public record AdminCreateRequest(
    String email, @Length(min = 6, max = 20, message = "密码长度错误") String password) {}
