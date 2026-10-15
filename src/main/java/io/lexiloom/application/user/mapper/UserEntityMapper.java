package io.lexiloom.application.user.mapper;

import io.lexiloom.application.user.api.dto.UserDTO;
import io.lexiloom.application.user.model.entity.UserEntity;
import io.lexiloom.application.user.service.impl.UserCommandServiceImpl.UserRegister;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

/**
 * @author yujian
 */
@Mapper
public interface UserEntityMapper {
  @Mapping(target = "phoneNumber", source = "register.phone.phoneNumber")
  @Mapping(target = "countryCode", source = "register.phone.countryCode")
  @Mapping(target = "phoneVerified", source = "register.phone.phoneVerified")
  @Mapping(target = "email", ignore = true)
  @Mapping(target = "emailVerified", ignore = true)
  UserEntity toUserEntity(UserRegister register);

  UserDTO toUserDTO(UserEntity user);

  @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
  @Mapping(target = "userName", source = "nickName")
  UserEntity updateEntity(String nickName, String avatarUrl, @MappingTarget UserEntity target);
}
