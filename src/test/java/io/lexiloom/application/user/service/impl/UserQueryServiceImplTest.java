package io.lexiloom.application.user.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.lexiloom.application.user.api.dto.UserDTO;
import io.lexiloom.application.user.mapper.UserEntityMapper;
import io.lexiloom.application.user.model.entity.UserEntity;
import io.lexiloom.application.user.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserQueryServiceImplTest {

  @Mock private UserRepository userRepository;
  @Mock private UserEntityMapper userEntityMapper;
  @InjectMocks private UserQueryServiceImpl service;

  @Test
  @DisplayName("isFirstLogin & getUserByUserId & getUserByEmail")
  void testQuery() {
    UserEntity entity = UserEntity.create("openid", "unionid");
    when(userRepository.findByOpenId("openid")).thenReturn(Optional.of(entity));
    when(userRepository.findById(1L)).thenReturn(Optional.of(entity));
    when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(entity));

    assertThat(service.isFirstLogin("openid")).isPresent();
    assertThat(service.getUserByUserId(1L)).isPresent();
    assertThat(service.getUserByEmail("test@example.com")).isPresent();
    assertThat(service.getUserByEmail("")).isEmpty();
  }

  @Test
  @DisplayName("findUserMapByIds")
  void testFindUserMapByIds() {
    assertThat(service.findUserMapByIds(List.of())).isEmpty();

    UserEntity entity = UserEntity.create("openId", "unionId");
    entity.setId(1L);
    UserDTO dto = new UserDTO(1L, "openId", null, "User1", null, null, false, null, null, false);

    when(userRepository.findAllById(List.of(1L))).thenReturn(List.of(entity));
    when(userEntityMapper.toUserDTO(entity)).thenReturn(dto);

    Map<Long, UserDTO> map = service.findUserMapByIds(List.of(1L));
    assertThat(map).hasSize(1);
    assertThat(map.get(1L).userName()).isEqualTo("User1");
  }
}
