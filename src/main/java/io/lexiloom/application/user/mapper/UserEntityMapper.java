package io.lexiloom.application.user.mapper;

import io.lexiloom.application.user.api.dto.UserDTO;
import io.lexiloom.application.user.model.entity.UserEntity;
import io.lexiloom.application.user.service.impl.UserCommandServiceImpl.UserRegister;
import org.mapstruct.Mapper;

/**
 * @author yujian
 */
@Mapper
public interface UserEntityMapper {

  default UserEntity toUserEntity(UserRegister register) {
    if (register == null) {
      return null;
    }
    String phone = register.phone() != null ? register.phone().phoneNumber() : null;
    String countryCode = register.phone() != null ? register.phone().countryCode() : null;
    Boolean phoneVerified = register.phone() != null ? register.phone().phoneVerified() : null;
    String email = register.email() != null ? register.email().email() : null;
    Boolean emailVerified = register.email() != null ? register.email().emailVerified() : null;
    return UserEntity.create(
        register.openId(),
        register.unionId(),
        phone,
        countryCode,
        phoneVerified,
        email,
        emailVerified,
        null,
        null);
  }

  UserDTO toUserDTO(UserEntity user);
}
