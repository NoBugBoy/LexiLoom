package io.lexiloom.application.user.model.request;

import io.lexiloom.application.enums.WorkSpaceRoleEnum;
import jakarta.validation.constraints.NotNull;

public record WorkSpaceMemberAddRequest(
    @NotNull(message = "用户ID不能为空") Long userId,
    @NotNull(message = "成员角色不能为空") WorkSpaceRoleEnum role) {}
