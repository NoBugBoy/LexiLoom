package com.knowflow.application.user.model.request;

import com.knowflow.application.enums.WorkSpaceRoleEnum;
import jakarta.validation.constraints.NotNull;

public record WorkSpaceMemberRoleUpdateRequest(
    @NotNull(message = "新角色不能为空") WorkSpaceRoleEnum role) {}
