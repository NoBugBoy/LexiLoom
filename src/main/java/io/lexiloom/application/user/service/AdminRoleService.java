package io.lexiloom.application.user.service;

import io.lexiloom.application.enums.AdminRoleEnum;
import java.util.List;

public interface AdminRoleService {

  /**
   * 查询管理员拥有的全部角色。
   *
   * @param userId 管理员 ID
   * @return 角色列表
   */
  List<AdminRoleEnum> findRolesByUserId(Long userId);

  /**
   * 判断管理员是否拥有指定角色。
   *
   * @param userId 管理员 ID
   * @param role 待检查的角色
   * @return 拥有返回 true，否则返回 false
   */
  boolean hasRole(Long userId, AdminRoleEnum role);

  /**
   * 为管理员分配单个角色。
   *
   * @param userId 管理员 ID
   * @param role 待分配的角色
   */
  void assignRole(Long userId, AdminRoleEnum role);

  /**
   * 为管理员批量分配角色。
   *
   * @param userId 管理员 ID
   * @param roles 待分配的角色列表
   */
  void assignRoles(Long userId, List<AdminRoleEnum> roles);

  /**
   * 删除管理员的全部角色关联。
   *
   * @param userId 管理员 ID
   */
  void deleteByUserId(Long userId);
}
