package io.lexiloom.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lexiloom.application.common.security.RedisTokenManager;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.model.entity.UserEntity;
import io.lexiloom.application.user.service.UserCommandService;
import io.lexiloom.application.user.service.UserEntityQueryService;
import io.lexiloom.application.user.usecase.LoginUseCase;
import io.lexiloom.application.user.usecase.LoginUseCase.LoginCommand;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoginUseCaseTest {

  @Mock private UserEntityQueryService userQueryService;

  @Mock private UserCommandService userCommandService;

  @Mock private RedisTokenManager redisTokenManager;

  @InjectMocks private LoginUseCase loginUseCase;

  @BeforeEach
  void setUp() {
    lenient().when(redisTokenManager.generateToken(any(), any())).thenReturn("mock-token-uuid");
  }

  @Test
  @DisplayName("首次登录：调用 register 注册并返回 token")
  void shouldRegisterOnFirstLogin() {
    var newUser = UserEntity.create("openid-1", null);
    newUser.setId(100L);

    when(userQueryService.isFirstLogin("openid-1")).thenReturn(Optional.empty());
    when(userCommandService.register(any())).thenReturn(newUser);

    var token = loginUseCase.execute(new LoginCommand("openid-1"));

    assertThat(token).as("accessToken").isNotBlank();
    verify(userCommandService).register(any());
  }

  @Test
  @DisplayName("老用户：不注册，直接返回 token")
  void shouldLoginExistingUserWithoutRegistering() {
    var user = UserEntity.create("openid-1", null);
    user.setId(42L);

    when(userQueryService.isFirstLogin("openid-1")).thenReturn(Optional.of(user));

    var token = loginUseCase.execute(new LoginCommand("openid-1"));

    assertThat(token).isNotBlank();
    verify(userCommandService, never()).register(any());
  }

  @Test
  @DisplayName("登录凭据为空：抛出 BusinessException")
  void shouldRejectBlankLoginCode() {
    var badCommand = new LoginCommand("  ");
    assertThatThrownBy(() -> loginUseCase.execute(badCommand))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("登录凭据不能为空");

    verify(userCommandService, never()).register(any());
  }
}
