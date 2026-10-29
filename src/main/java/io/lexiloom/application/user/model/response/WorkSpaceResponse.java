package io.lexiloom.application.user.model.response;

import cn.hutool.core.date.DatePattern;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
import io.lexiloom.application.enums.WorkSpaceStatusEnum;
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
    Long parentId,
    Boolean childAssocEnabled,
    @JsonFormat(pattern = DatePattern.NORM_DATETIME_PATTERN) LocalDateTime createTime) {}
