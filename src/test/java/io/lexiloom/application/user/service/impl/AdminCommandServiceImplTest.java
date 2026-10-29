package io.lexiloom.application.user.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import io.lexiloom.application.common.LoginUserAuthentication;
import io.lexiloom.application.common.cache.CacheHelper;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.api.dto.AdminResetPasswordDTO;
import io.lexiloom.application.user.cache.AccountCacheKey;
import io.lexiloom.application.user.mapper.admin.AdminEntityMapper;
import io.lexiloom.application.user.model.entity.AdminEntity;
import io.lexiloom.application.user.repository.AdminRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AdminCommandServiceImplTest {

  @Mock private AdminEntityMapper adminEntityMapper;

  @Mock private AdminRepository adminRepository;

  @Mock private PasswordEncoder passwordEncoder;

  @Mock private CacheHelper cacheHelper;

  @InjectMocks private AdminCommandServiceImpl service;

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("保存管理员")
  void saveAdminUser() {
    AdminEntity entity = AdminEntity.create("test@test.com", "pwd", true);
    entity.setId(1L);
    when(adminEntityMapper.toAdminEntity("test@test.com", "pwd", true)).thenReturn(entity);
    when(adminRepository.save(entity)).thenReturn(entity);

    Long id = service.saveAdminUser("test@test.com", "pwd");

    assertThat(id).isEqualTo(1L);
  }

  @Test
  @DisplayName("重置密码 (用户内) - 密码错误")
  void resetPasswordInternalWrongPwd() {
    SecurityContextHolder.getContext()
        .setAuthentication(new LoginUserAuthentication(1L, List.of("user")));
    AdminEntity entity = AdminEntity.create("test@test.com", "encoded", true);

    when(adminRepository.findById(1L)).thenReturn(Optional.of(entity));
    when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);

    assertThatThrownBy(() -> service.resetPassword("wrong", "newpwd"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("旧密码错误");
  }

  @Test
  @DisplayName("重置密码 (用户内) - 成功")
  void resetPasswordInternalSuccess() {
    SecurityContextHolder.getContext()
        .setAuthentication(new LoginUserAuthentication(1L, List.of("user")));
    AdminEntity entity = AdminEntity.create("test@test.com", "encoded", true);

    when(adminRepository.findById(1L)).thenReturn(Optional.of(entity));
    when(passwordEncoder.matches("old", "encoded")).thenReturn(true);
    when(passwordEncoder.encode("new")).thenReturn("encodedNew");

    service.resetPassword("old", "new");

    assertThat(entity.getPassword()).isEqualTo("encodedNew");
    verify(adminRepository).saveAndFlush(entity);
  }

  @Test
  @DisplayName("重置密码 (OTT) - 链接无效")
  void resetPasswordOttInvalid() {
    when(cacheHelper.getAndEvict(AccountCacheKey.OTT_RESET_PASSWORD, "invalid"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.resetPassword("invalid"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("链接不存在或已失效");
  }

  @Test
  @DisplayName("重置密码 (OTT) - 用户不存在")
  void resetPasswordOttUserNotFound() {
    AdminResetPasswordDTO dto = new AdminResetPasswordDTO("test@test.com", "new");
    when(cacheHelper.getAndEvict(AccountCacheKey.OTT_RESET_PASSWORD, "valid"))
        .thenReturn(Optional.of(dto));
    when(adminRepository.findByEmail("test@test.com")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.resetPassword("valid"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("账号不存在");
  }

  @Test
  @DisplayName("重置密码 (OTT) - 成功")
  void resetPasswordOttSuccess() {
    AdminResetPasswordDTO dto = new AdminResetPasswordDTO("test@test.com", "new");
    when(cacheHelper.getAndEvict(AccountCacheKey.OTT_RESET_PASSWORD, "valid"))
        .thenReturn(Optional.of(dto));

    AdminEntity entity = AdminEntity.create("test@test.com", "oldpwd", true);
    when(adminRepository.findByEmail("test@test.com")).thenReturn(Optional.of(entity));
    when(passwordEncoder.encode("new")).thenReturn("encodedNew");

    service.resetPassword("valid");

    assertThat(entity.getPassword()).isEqualTo("encodedNew");
    verify(adminRepository).saveAndFlush(entity);
  }
}
