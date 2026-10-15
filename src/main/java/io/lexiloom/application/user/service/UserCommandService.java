package io.lexiloom.application.user.service;

import io.lexiloom.application.user.model.entity.UserEntity;
import io.lexiloom.application.user.service.impl.UserCommandServiceImpl.UserRegister;
import org.springframework.validation.annotation.Validated;

/**
 * @author yujian
 */
public interface UserCommandService {

  /**
   * 注册新用户（C 端首次微信登录时调用）。
   *
   * @param userRegister 用户注册信息
   * @return 保存后的用户实体
   */
  UserEntity register(@Validated UserRegister userRegister);

  /**
   * 更新当前登录用户的昵称与头像；用户不存在时抛出业务异常。
   *
   * @param nickName 新昵称
   * @param avatarUrl 新头像 URL
   */
  void updateProfile(String nickName, String avatarUrl);
}
