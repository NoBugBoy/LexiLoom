package io.lexiloom.application.document.spacelink.model.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 申请工作区单向横向关联请求参数 (不可变 record) */
public record SpaceLinkCreateRequest(
    @NotNull(message = "目标工作区ID不能为空") Long targetWorkspaceId,
    @NotEmpty(message = "请至少选择一个关联文档") List<Long> documentIds,
    @Size(max = 500, message = "申请理由最多500字符") String applyReason) {}
