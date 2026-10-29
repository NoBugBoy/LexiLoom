package io.lexiloom.application.user.service.impl;

import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.service.UserEntityQueryService;
import io.lexiloom.application.user.api.dto.Email;
import io.lexiloom.application.user.api.dto.Phone;
import io.lexiloom.application.user.mapper.UserEntityMapper;
import io.lexiloom.application.user.model.entity.UserEntity;
import io.lexiloom.application.user.repository.UserRepository;
import io.lexiloom.application.user.service.UserCommandService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author yujian
 */
@Service
@RequiredArgsConstructor
public class UserCommandServiceImpl implements UserCommandService {
  private final UserRepository userRepository;
  private final UserEntityQueryService userQueryService;
  private final UserEntityMapper userEntityMapper;

  public record UserRegister(
      @NotBlank(message = "openId不能为空") String openId,
      @NotBlank(message = "unionId不能为空") String unionId,
      Phone phone,
      Email email) {}

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public UserEntity register(UserRegister userRegister) {
    var userEntity = userEntityMapper.toUserEntity(userRegister);
    return userRepository.save(userEntity);
  }

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public void updateProfile(String nickName, String avatarUrl) {
    var user =
        userQueryService
            .getUserByUserId(SecurityHolder.getUserId())
            .orElseThrow(() -> BusinessException.badRequest("用户不存在"));
    user.updateProfile(nickName, avatarUrl);
    userRepository.saveAndFlush(user);
  }
}
