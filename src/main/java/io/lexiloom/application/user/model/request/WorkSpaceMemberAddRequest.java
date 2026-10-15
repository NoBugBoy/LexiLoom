package io.lexiloom.application.user.model.request;

import io.lexiloom.application.enums.WorkSpaceRoleEnum;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** 团队添加成员请求 DTO */
public record WorkSpaceMemberAddRequest(
    @NotBlank(message = "邮箱不能为空") @Email(message = "邮箱格式不正确") String email,
    WorkSpaceRoleEnum role) {

  public WorkSpaceMemberAddRequest {
    email = email != null ? email.trim() : null;
    if (role == null) {
      role = WorkSpaceRoleEnum.MEMBER;
    }
  }

  public static WorkSpaceMemberAddRequest of(String email) {
    return new WorkSpaceMemberAddRequest(email, WorkSpaceRoleEnum.MEMBER);
  }

  public static WorkSpaceMemberAddRequest of(String email, WorkSpaceRoleEnum role) {
    return new WorkSpaceMemberAddRequest(email, role);
  }
}
