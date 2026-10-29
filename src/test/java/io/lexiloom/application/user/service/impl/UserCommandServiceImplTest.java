package io.lexiloom.application.user.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import io.lexiloom.application.common.LoginUserAuthentication;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.service.UserEntityQueryService;
import io.lexiloom.application.user.api.dto.Email;
import io.lexiloom.application.user.api.dto.Phone;
import io.lexiloom.application.user.mapper.UserEntityMapper;
import io.lexiloom.application.user.model.entity.UserEntity;
import io.lexiloom.application.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class UserCommandServiceImplTest {

  @Mock private UserRepository userRepository;

  @Mock private UserEntityQueryService userQueryService;

  @Mock private UserEntityMapper userEntityMapper;

  @InjectMocks private UserCommandServiceImpl service;

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("用户注册")
  void register() {
    UserCommandServiceImpl.UserRegister register =
        new UserCommandServiceImpl.UserRegister(
            "openid",
            "unionid",
            new Phone("13800138000"),
            new Email("test@test.com", "1234", false));
    UserEntity entity = UserEntity.create("openid", "unionid");

    when(userEntityMapper.toUserEntity(register)).thenReturn(entity);
    when(userRepository.save(entity)).thenReturn(entity);

    UserEntity result = service.register(register);

    assertThat(result).isNotNull();
    verify(userRepository).save(entity);
  }

  @Test
  @DisplayName("更新资料 - 用户不存在")
  void updateProfileUserNotFound() {
    LoginUserAuthentication auth = new LoginUserAuthentication(1L, java.util.List.of("user"));
    SecurityContextHolder.getContext().setAuthentication(auth);

    when(userQueryService.getUserByUserId(1L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.updateProfile("nick", "avatar"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("用户不存在");
  }

  @Test
  @DisplayName("更新资料 - 成功")
  void updateProfileSuccess() {
    LoginUserAuthentication auth = new LoginUserAuthentication(1L, java.util.List.of("user"));
    SecurityContextHolder.getContext().setAuthentication(auth);

    UserEntity entity = UserEntity.create("openid", "unionid");
    when(userQueryService.getUserByUserId(1L)).thenReturn(Optional.of(entity));

    service.updateProfile("nick", "avatar");

    assertThat(entity.getUserName()).isEqualTo("nick");
    assertThat(entity.getAvatarUrl()).isEqualTo("avatar");
    verify(userRepository).saveAndFlush(entity);
  }
}
