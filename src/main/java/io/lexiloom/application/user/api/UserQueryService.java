package io.lexiloom.application.user.api;

import io.lexiloom.application.user.model.entity.UserEntity;
import java.util.Optional;

/**
 * @author yujian
 */
public interface UserQueryService {

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

  /**
   * 批量查询用户传输对象映射 (Key: userId, Value: UserDTO)
   *
   * @param userIds 用户 ID 集合
   * @return 用户传输对象映射
   */
  java.util.Map<Long, io.lexiloom.application.user.api.dto.UserDTO> findUserMapByIds(
      java.util.Collection<Long> userIds);
}
