package io.lexiloom.application.enums;

import lombok.Getter;

@Getter
public enum AdminRoleEnum {
  /** 超级管理员（仅限一个） */
  SUPER_ADMIN(100),
  /** 管理员 */
  ADMIN(50),
  /** 普通成员 */
  MEMBER(30),
  /** 访客 */
  GUEST(10);

  /** 权限权重（数值越大权限越高，方便做权限越级判断：currentUser.level >= KB_ADMIN.level） */
  private final int level;

  AdminRoleEnum(int level) {
    this.level = level;
  }

  /** 判断当前角色是否有权管理另一个角色（权限比较小技巧） */
  public boolean canManage(AdminRoleEnum targetRole) {
    return this.level > targetRole.getLevel();
  }
}
