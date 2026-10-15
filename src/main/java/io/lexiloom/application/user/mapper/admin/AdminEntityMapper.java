package io.lexiloom.application.user.mapper.admin;

import io.lexiloom.application.user.model.entity.AdminEntity;
import org.mapstruct.Mapper;

@Mapper
public interface AdminEntityMapper {

  AdminEntity toAdminEntity(String email, String password, Boolean emailVerified);
}
