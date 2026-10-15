package io.lexiloom.application.user.service.impl;

import io.lexiloom.application.enums.AdminRoleEnum;
import io.lexiloom.application.user.mapper.admin.AdminRoleMapper;
import io.lexiloom.application.user.model.entity.AdminRoleEntity;
import io.lexiloom.application.user.repository.AdminRoleRepository;
import io.lexiloom.application.user.service.AdminRoleService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminRoleServiceImpl implements AdminRoleService {

  private final AdminRoleRepository adminRoleRepository;
  private final AdminRoleMapper adminRoleMapper;

  @Override
  public List<AdminRoleEnum> findRolesByUserId(Long userId) {
    return adminRoleRepository.findByUserId(userId).stream().map(AdminRoleEntity::getRole).toList();
  }

  @Override
  public boolean hasRole(Long userId, AdminRoleEnum role) {
    return adminRoleRepository.existsByUserIdAndRole(userId, role);
  }

  @Override
  public void assignRole(Long userId, AdminRoleEnum role) {
    AdminRoleEntity adminRoleEntity = adminRoleMapper.toAdminRoleEntity(userId, role);
    adminRoleRepository.save(adminRoleEntity);
  }

  @Override
  public void assignRoles(Long userId, List<AdminRoleEnum> roles) {
    // 预留批量分配角色逻辑，暂不实现。
  }

  @Override
  public void deleteByUserId(Long userId) {
    // 预留删除用户角色逻辑，暂不实现。
  }
}
