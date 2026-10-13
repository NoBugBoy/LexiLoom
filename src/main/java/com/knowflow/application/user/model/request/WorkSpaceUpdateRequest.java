package com.knowflow.application.user.model.request;

import com.knowflow.application.enums.WorkSpaceAclEnum;
import jakarta.validation.constraints.Size;

public record WorkSpaceUpdateRequest(
    @Size(max = 100, message = "团队名称长度不能超过100个字符") String name,
    @Size(max = 500, message = "团队简介长度不能超过500个字符") String description,
    String avatarUrl,
    WorkSpaceAclEnum visibility) {}
