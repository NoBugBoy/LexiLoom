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

  @Override
  public java.util.Map<Long, io.lexiloom.application.user.api.dto.UserDTO> findUserMapByIds(
      java.util.Collection<Long> userIds) {
    if (userIds == null || userIds.isEmpty()) {
      return java.util.Map.of();
    }
    return userRepository.findAllById(userIds).stream()
        .collect(
            java.util.stream.Collectors.toMap(
                UserEntity::getId,
                u ->
                    new io.lexiloom.application.user.api.dto.UserDTO(
                        u.getId(),
                        u.getOpenId(),
                        u.getUnionId(),
                        u.getUserName(),
                        u.getAvatarUrl(),
                        u.getEmail(),
                        u.getEmailVerified(),
                        u.getPhoneNumber(),
                        u.getCountryCode(),
                        u.getPhoneVerified()),
                (u1, u2) -> u1));
  }
}
