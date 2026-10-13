package com.knowflow.application.user.usecase.admin;

import com.knowflow.application.common.UseCase;
import com.knowflow.application.common.cache.CacheHelper;
import com.knowflow.application.common.security.RedisTokenManager;
import com.knowflow.application.enums.AdminRoleEnum;
import com.knowflow.application.exception.BusinessException;
import com.knowflow.application.user.api.AdminQueryService;
import com.knowflow.application.user.cache.AccountCacheKey;
import com.knowflow.application.user.service.AdminCommandService;
import com.knowflow.application.user.service.AdminRoleService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminRegisterUseCase implements UseCase<String, String>, CommandLineRunner {

  private final AdminCommandService adminCommandService;
  private final AdminQueryService adminQueryService;
  private final AdminRoleService adminRoleService;
  private final PasswordEncoder passwordEncoder;
  private final CacheHelper cacheHelper;
  private final RedisTokenManager redisTokenManager;

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public String execute(String ott) {
    return cacheHelper
        .getAndEvict(AccountCacheKey.OTT_REGISTER, ott)
        .map(
            v -> {
              var encodePassword = passwordEncoder.encode(v.password());
              var adminUserId = adminCommandService.saveAdminUser(v.email(), encodePassword);
              adminRoleService.assignRole(adminUserId, AdminRoleEnum.ADMIN);
              return redisTokenManager.generateToken(adminUserId, List.of(AdminRoleEnum.ADMIN));
            })
        .orElseThrow(() -> BusinessException.badRequest("链接不存在或已经失效"));
  }

  @Override
  public void run(String... args) throws Exception {
    var email = "admin@knowflow.com";
    if (adminQueryService.findAdminByEmail(email).isEmpty()) {
      String password = passwordEncoder.encode("123456");
      Long superAdminId = adminCommandService.saveAdminUser(email, password);
      adminRoleService.assignRole(superAdminId, AdminRoleEnum.SUPER_ADMIN);
    }
  }
}
