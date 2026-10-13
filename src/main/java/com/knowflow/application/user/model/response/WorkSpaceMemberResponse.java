package com.knowflow.application.user.model.response;

import com.knowflow.application.enums.WorkSpaceRoleEnum;
import java.time.LocalDateTime;

public record WorkSpaceMemberResponse(
    Long id, Long workSpaceId, Long userId, WorkSpaceRoleEnum role, LocalDateTime createTime) {}
