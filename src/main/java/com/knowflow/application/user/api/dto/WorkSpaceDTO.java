package com.knowflow.application.user.api.dto;

import com.knowflow.application.enums.WorkSpaceAclEnum;
import com.knowflow.application.enums.WorkSpaceStatusEnum;
import java.time.LocalDateTime;

public record WorkSpaceDTO(
    Long id,
    String name,
    String description,
    String code,
    String avatarUrl,
    Long ownerId,
    Integer maxMembers,
    WorkSpaceStatusEnum status,
    WorkSpaceAclEnum visibility,
    LocalDateTime createTime) {}
