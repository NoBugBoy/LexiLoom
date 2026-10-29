package io.lexiloom.application.user.service.impl;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.user.api.UserQueryService;
import io.lexiloom.application.user.api.dto.UserDTO;
import io.lexiloom.application.user.mapper.UserEntityMapper;
import io.lexiloom.application.user.model.entity.UserEntity;
import io.lexiloom.application.user.repository.UserRepository;
import io.lexiloom.application.user.service.UserEntityQueryService;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * @author yujian
 */
@Service
@RequiredArgsConstructor
public class UserQueryServiceImpl implements UserQueryService, UserEntityQueryService {
  private final UserRepository userRepository;
  private final UserEntityMapper userEntityMapper;

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
    if (StrUtil.isBlank(email)) {
      return Optional.empty();
    }
    return userRepository.findByEmail(email.trim());
  }

  @Override
  public Map<Long, UserDTO> findUserMapByIds(Collection<Long> userIds) {
    if (userIds == null || userIds.isEmpty()) {
      return Map.of();
    }
    return userRepository.findAllById(userIds).stream()
        .collect(
            Collectors.toMap(
                UserEntity::getId,
                userEntityMapper::toUserDTO,
                (u1, u2) -> u1));
  }
}
