package com.knowflow.application.user.model.entity;

import com.knowflow.application.common.BaseEntity;
import com.knowflow.application.enums.WorkSpaceRoleEnum;
import com.knowflow.application.exception.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 团队成员实体 */
@Entity
@Table(
    name = "kf_work_space_member",
    comment = "团队成员表",
    indexes = {
      @Index(name = "uk_work_space_user", unique = true, columnList = "work_space_id, user_id")
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkSpaceMemberEntity extends BaseEntity {

  @Column(name = "work_space_id", nullable = false)
  private Long workSpaceId;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private WorkSpaceRoleEnum role;

  /** 静态工厂：创建团队成员关联 */
  public static WorkSpaceMemberEntity create(
      Long workSpaceId, Long userId, WorkSpaceRoleEnum role) {
    Objects.requireNonNull(workSpaceId, "团队ID不能为空");
    Objects.requireNonNull(userId, "用户ID不能为空");
    Objects.requireNonNull(role, "成员角色不能为空");

    WorkSpaceMemberEntity member = new WorkSpaceMemberEntity();
    member.workSpaceId = workSpaceId;
    member.userId = userId;
    member.role = role;
    return member;
  }

  /** 变更角色（普通角色流转，禁止直接降级 OWNER） */
  public void changeRole(WorkSpaceRoleEnum newRole) {
    Objects.requireNonNull(newRole, "新角色不能为空");
    if (this.role == WorkSpaceRoleEnum.OWNER && newRole != WorkSpaceRoleEnum.OWNER) {
      throw BusinessException.badRequest("不能直接降级团队所有者，请使用团队所有权转让功能");
    }
    this.role = newRole;
  }

  /** 所有权转让时的所有者角色降级 */
  public void demoteFromOwner(WorkSpaceRoleEnum newRole) {
    Objects.requireNonNull(newRole, "新角色不能为空");
    if (this.role != WorkSpaceRoleEnum.OWNER) {
      throw BusinessException.badRequest("当前成员不是所有者，无需降级");
    }
    this.role = newRole;
  }

  public boolean isOwner() {
    return this.role == WorkSpaceRoleEnum.OWNER;
  }

  public boolean hasAtLeastRole(WorkSpaceRoleEnum requiredRole) {
    return this.role != null && this.role.getLevel() >= requiredRole.getLevel();
  }
}
