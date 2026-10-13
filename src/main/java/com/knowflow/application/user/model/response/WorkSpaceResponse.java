package com.knowflow.application.user.model.response;

import com.knowflow.application.enums.WorkSpaceAclEnum;
import com.knowflow.application.enums.WorkSpaceStatusEnum;
import java.time.LocalDateTime;

public record WorkSpaceResponse(
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
