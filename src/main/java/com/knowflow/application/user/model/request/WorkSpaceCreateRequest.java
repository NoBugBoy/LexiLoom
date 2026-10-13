package com.knowflow.application.user.model.request;

import com.knowflow.application.enums.WorkSpaceAclEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record WorkSpaceCreateRequest(
    @NotBlank(message = "团队名称不能为空") @Size(max = 100, message = "团队名称长度不能超过100个字符") String name,
    @NotBlank(message = "团队标识(code)不能为空")
        @Pattern(regexp = "^[a-zA-Z0-9_-]{2,50}$", message = "团队标识只能包含字母、数字、下划线和短横线，长度在2-50之间")
        String code,
    @Size(max = 500, message = "团队简介长度不能超过500个字符") String description,
    String avatarUrl,
    Integer maxMembers,
    WorkSpaceAclEnum visibility) {}
