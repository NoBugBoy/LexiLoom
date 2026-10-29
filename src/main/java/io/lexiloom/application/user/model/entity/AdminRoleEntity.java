package io.lexiloom.application.user.model.entity;

import io.lexiloom.application.common.BaseEntity;
import io.lexiloom.application.enums.AdminRoleEnum;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 管理员角色关联实体 (充血领域模型) */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "ll_admin_role", comment = "管理员角色关联表")
public class AdminRoleEntity extends BaseEntity {

  /** 管理员用户ID */
  @Column(name = "user_id", comment = "管理员用户ID", nullable = false)
  private Long userId;

  /** 管理员角色枚举 */
  @Column(comment = "管理员角色枚举", nullable = false, length = 32)
  @Enumerated(EnumType.STRING)
  private AdminRoleEnum role;

  /**
   * 静态工厂创建管理员角色关联
   */
  public static AdminRoleEntity create(Long userId, AdminRoleEnum role) {
    Objects.requireNonNull(userId, "用户ID不能为空");
    Objects.requireNonNull(role, "角色不能为空");
    AdminRoleEntity entity = new AdminRoleEntity();
    entity.userId = userId;
    entity.role = role;
    return entity;
  }
}
