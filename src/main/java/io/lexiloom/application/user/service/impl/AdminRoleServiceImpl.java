package io.lexiloom.application.user.service.impl;

import io.lexiloom.application.enums.AdminRoleEnum;
import io.lexiloom.application.user.mapper.admin.AdminRoleMapper;
import io.lexiloom.application.user.model.entity.AdminRoleEntity;
import io.lexiloom.application.user.repository.AdminRoleRepository;
import io.lexiloom.application.user.service.AdminRoleService;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminRoleServiceImpl implements AdminRoleService {

  private final AdminRoleRepository adminRoleRepository;
  private final AdminRoleMapper adminRoleMapper;

  @Override
  public List<AdminRoleEnum> findRolesByUserId(Long userId) {
    if (userId == null) {
      return List.of();
    }
    return adminRoleRepository.findByUserId(userId).stream().map(AdminRoleEntity::getRole).toList();
  }

  @Override
  public boolean hasRole(Long userId, AdminRoleEnum role) {
    if (userId == null || role == null) {
      return false;
    }
    return adminRoleRepository.existsByUserIdAndRole(userId, role);
  }

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public void assignRole(Long userId, AdminRoleEnum role) {
    if (userId == null || role == null) {
      return;
    }
    if (adminRoleRepository.existsByUserIdAndRole(userId, role)) {
      return;
    }
    AdminRoleEntity adminRoleEntity = adminRoleMapper.toAdminRoleEntity(userId, role);
    adminRoleRepository.save(adminRoleEntity);
  }

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public void assignRoles(Long userId, List<AdminRoleEnum> roles) {
    if (userId == null || roles == null || roles.isEmpty()) {
      return;
    }
    roles.stream().filter(Objects::nonNull).distinct().forEach(role -> assignRole(userId, role));
  }

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public void deleteByUserId(Long userId) {
    if (userId == null) {
      return;
    }
    adminRoleRepository.deleteByUserId(userId);
  }
}
