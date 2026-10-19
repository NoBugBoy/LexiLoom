package io.lexiloom.application.user.api.dto;

import io.lexiloom.application.enums.WorkSpaceAclEnum;
import io.lexiloom.application.enums.WorkSpaceStatusEnum;
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
    Long parentId,
    Boolean childAssocEnabled,
    LocalDateTime createTime) {}
