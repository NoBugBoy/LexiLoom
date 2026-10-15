package io.lexiloom.application.common.security;

import io.lexiloom.application.enums.AdminRoleEnum;
import java.util.List;

public record LoginToken(Long userId, List<AdminRoleEnum> roles) {}
