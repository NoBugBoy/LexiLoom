package io.lexiloom.application.user.model.request;

import io.lexiloom.application.enums.WorkSpaceRoleEnum;
import jakarta.validation.constraints.NotNull;

public record WorkSpaceMemberRoleUpdateRequest(
    @NotNull(message = "新角色不能为空") WorkSpaceRoleEnum role) {}
