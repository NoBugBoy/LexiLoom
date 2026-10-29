package io.lexiloom.application.user.usecase.admin;

import io.lexiloom.application.common.UseCase;
import io.lexiloom.application.common.security.RedisTokenManager;
import io.lexiloom.application.common.security.TurnstileVerifier;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.service.AdminQueryService;
import io.lexiloom.application.user.model.entity.AdminEntity;
import io.lexiloom.application.user.model.request.AdminLoginRequest;
import io.lexiloom.application.user.service.AdminRoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminLoginUseCase implements UseCase<AdminLoginRequest, String> {
  private final PasswordEncoder passwordEncoder;
  private final AdminQueryService adminQueryService;
  private final AdminRoleService adminRoleService;
  private final RedisTokenManager redisTokenManager;
  private final TurnstileVerifier turnstileVerifier;

  @Override
  public String execute(AdminLoginRequest command) {
    try {
      turnstileVerifier.verify(command.turnstileToken());
    } catch (IllegalArgumentException exception) {
      throw BusinessException.badRequest(exception.getMessage());
    }

    AdminEntity adminEntity =
        adminQueryService
            .findAdminByEmail(command.email())
            .filter(it -> passwordEncoder.matches(command.password(), it.getPassword()))
            .orElseThrow(() -> BusinessException.badRequest("用户名或密码错误"));
    var roles = adminRoleService.findRolesByUserId(adminEntity.getId());

    return redisTokenManager.generateToken(adminEntity.getId(), roles);
  }
}
