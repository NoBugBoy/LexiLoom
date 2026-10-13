package com.knowflow.application.user.api.dto;

import com.knowflow.application.enums.WorkSpaceRoleEnum;
import java.time.LocalDateTime;

public record WorkSpaceMemberDTO(
    Long id, Long workSpaceId, Long userId, WorkSpaceRoleEnum role, LocalDateTime createTime) {}
