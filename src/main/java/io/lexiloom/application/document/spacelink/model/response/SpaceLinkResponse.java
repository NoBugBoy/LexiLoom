package io.lexiloom.application.document.spacelink.model.response;

import io.lexiloom.application.enums.SpaceLinkStatusEnum;
import java.time.LocalDateTime;
import java.util.List;

/** SpaceLink 单向关联详情响应 (不可变 record) */
public record SpaceLinkResponse(
    Long id,
    Long sourceWorkspaceId,
    String sourceWorkspaceName,
    Long targetWorkspaceId,
    String targetWorkspaceName,
    SpaceLinkStatusEnum status,
    String rejectReason,
    List<Long> documentIds,
    LocalDateTime createTime,
    LocalDateTime updateTime) {}
