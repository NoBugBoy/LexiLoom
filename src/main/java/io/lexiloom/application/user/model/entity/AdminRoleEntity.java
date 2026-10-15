package io.lexiloom.application.user.model.entity;

import io.lexiloom.application.common.BaseEntity;
import io.lexiloom.application.enums.AdminRoleEnum;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "ll_admin_role")
public class AdminRoleEntity extends BaseEntity {
  @Column(comment = "管理员id")
  private Long userId;

  @Column(comment = "管理员id")
  @Enumerated(EnumType.STRING)
  private AdminRoleEnum role;
}
