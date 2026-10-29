package io.lexiloom.application.user.model.response;

import cn.hutool.core.date.DatePattern;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.lexiloom.application.enums.WorkSpaceRoleEnum;
import java.time.LocalDateTime;

public record WorkSpaceMemberResponse(
    Long id,
    Long workSpaceId,
    Long userId,
    WorkSpaceRoleEnum role,
    @JsonFormat(pattern = DatePattern.NORM_DATETIME_PATTERN) LocalDateTime createTime) {}
