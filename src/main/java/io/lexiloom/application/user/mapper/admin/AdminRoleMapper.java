package io.lexiloom.application.user.mapper.admin;

import io.lexiloom.application.enums.AdminRoleEnum;
import io.lexiloom.application.user.model.entity.AdminRoleEntity;
import org.mapstruct.Mapper;

@Mapper
public interface AdminRoleMapper {

  default AdminRoleEntity toAdminRoleEntity(Long userId, AdminRoleEnum role) {
    return AdminRoleEntity.create(userId, role);
  }
}
