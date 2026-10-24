package io.lexiloom.application.user.model.entity;

import io.lexiloom.application.common.BaseEntity;
import io.lexiloom.application.enums.AdminRoleEnum;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 管理员角色关联实体 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "ll_admin_role", comment = "管理员角色关联表")
public class AdminRoleEntity extends BaseEntity {

  /** 管理员用户ID */
  @Column(name = "user_id", comment = "管理员用户ID", nullable = false)
  private Long userId;

  /** 管理员角色枚举 */
  @Column(comment = "管理员角色枚举", nullable = false, length = 32)
  @Enumerated(EnumType.STRING)
  private AdminRoleEnum role;
}
