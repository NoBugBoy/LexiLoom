package io.lexiloom.application.user.model.response;

import io.lexiloom.application.enums.WorkSpaceRoleEnum;
import java.time.LocalDateTime;

public record WorkSpaceMemberResponse(
    Long id, Long workSpaceId, Long userId, WorkSpaceRoleEnum role, LocalDateTime createTime) {}
