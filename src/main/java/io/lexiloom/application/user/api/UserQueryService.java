package io.lexiloom.application.user.api;

import io.lexiloom.application.user.api.dto.UserDTO;
import java.util.Collection;
import java.util.Map;

/**
 * 用户查询服务（跨模块公开，仅暴露 DTO）
 *
 * @author yujian
 */
public interface UserQueryService {

  /**
   * 批量查询用户传输对象映射 (Key: userId, Value: UserDTO)
   *
   * @param userIds 用户 ID 集合
   * @return 用户传输对象映射
   */
  Map<Long, UserDTO> findUserMapByIds(Collection<Long> userIds);
}
