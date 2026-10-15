package io.lexiloom.application.user.service.impl;

import io.lexiloom.application.user.api.UserQueryService;
import io.lexiloom.application.user.model.entity.UserEntity;
import io.lexiloom.application.user.repository.UserRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * @author yujian
 */
@Service
@RequiredArgsConstructor
public class UserQueryServiceImpl implements UserQueryService {
  private final UserRepository userRepository;

  @Override
  public Optional<UserEntity> isFirstLogin(String openId) {
    return userRepository.findByOpenId(openId);
  }

  @Override
  public Optional<UserEntity> getUserByUserId(Long userId) {
    return userRepository.findById(userId);
  }

  @Override
  public Optional<UserEntity> getUserByEmail(String email) {
    if (cn.hutool.core.util.StrUtil.isBlank(email)) {
      return Optional.empty();
    }
    return userRepository.findByEmail(email.trim());
  }
}
