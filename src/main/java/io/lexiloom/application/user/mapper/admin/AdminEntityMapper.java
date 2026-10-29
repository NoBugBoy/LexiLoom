package io.lexiloom.application.user.mapper.admin;

import io.lexiloom.application.user.model.entity.AdminEntity;
import org.mapstruct.Mapper;

@Mapper
public interface AdminEntityMapper {

  default AdminEntity toAdminEntity(String email, String password, Boolean emailVerified) {
    return AdminEntity.create(email, password, emailVerified);
  }
}
