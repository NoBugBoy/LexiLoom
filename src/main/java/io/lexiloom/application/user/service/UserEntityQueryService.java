package io.lexiloom.application.user.service;

import io.lexiloom.application.user.model.entity.UserEntity;
import java.util.Optional;

/** 用户实体查询服务（user 模块内部使用，不对外暴露 Entity） */
public interface UserEntityQueryService {

  /**
   * 按 openId 查询用户，用于判断是否首次登录。
   *
   * @param openId 微信 openId
   * @return 匹配的用户；不存在时返回 empty
   */
  Optional<UserEntity> isFirstLogin(String openId);

  /**
   * 按用户 ID 查询用户。
   *
   * @param userId 用户 ID
   * @return 匹配的用户；不存在时返回 empty
   */
  Optional<UserEntity> getUserByUserId(Long userId);

  /**
   * 按用户邮箱查询用户。
   *
   * @param email 用户邮箱
   * @return 匹配的用户；不存在时返回 empty
   */
  Optional<UserEntity> getUserByEmail(String email);
}
