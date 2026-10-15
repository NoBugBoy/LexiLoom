package io.lexiloom.application.user.service.impl;

import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.common.cache.CacheHelper;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.cache.AccountCacheKey;
import io.lexiloom.application.user.mapper.admin.AdminEntityMapper;
import io.lexiloom.application.user.model.entity.AdminEntity;
import io.lexiloom.application.user.repository.AdminRepository;
import io.lexiloom.application.user.service.AdminCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminCommandServiceImpl implements AdminCommandService {
  private final AdminEntityMapper adminEntityMapper;
  private final AdminRepository adminRepository;
  private final PasswordEncoder passwordEncoder;
  private final CacheHelper cacheHelper;

  @Override
  public Long saveAdminUser(String email, String password) {
    AdminEntity adminEntity = adminEntityMapper.toAdminEntity(email, password, true);

    return adminRepository.save(adminEntity).getId();
  }

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public void resetPassword(String password, String newPassword) {
    Long userId = SecurityHolder.getUserId();
    AdminEntity admin =
        adminRepository
            .findById(userId)
            .filter(adminEntity -> passwordEncoder.matches(password, adminEntity.getPassword()))
            .orElseThrow(() -> BusinessException.badRequest("旧密码错误"));
    admin.setPassword(passwordEncoder.encode(newPassword));
    adminRepository.saveAndFlush(admin);
  }

  @Override
  public void resetPassword(String ott) {
    cacheHelper
        .getAndEvict(AccountCacheKey.OTT_RESET_PASSWORD, ott)
        .map(
            it -> {
              adminRepository
                  .findByEmail(it.email())
                  .map(
                      admin -> {
                        admin.setPassword(passwordEncoder.encode(it.newPassword()));
                        adminRepository.saveAndFlush(admin);
                        return it;
                      })
                  .orElseThrow(() -> BusinessException.badRequest("账号不存在，或已经被删除"));
              return it;
            })
        .orElseThrow(() -> BusinessException.badRequest("链接不存在或已失效"));
  }
}
