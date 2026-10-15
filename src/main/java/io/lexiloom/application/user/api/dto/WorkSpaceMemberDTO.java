package io.lexiloom.application.user.api.dto;

import io.lexiloom.application.enums.WorkSpaceRoleEnum;
import java.time.LocalDateTime;

public record WorkSpaceMemberDTO(
    Long id, Long workSpaceId, Long userId, WorkSpaceRoleEnum role, LocalDateTime createTime) {}
