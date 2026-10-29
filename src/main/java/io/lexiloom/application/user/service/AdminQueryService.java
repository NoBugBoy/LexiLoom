package io.lexiloom.application.user.service;

import io.lexiloom.application.user.model.entity.AdminEntity;
import io.lexiloom.application.user.model.request.AdminPageRequest;
import io.lexiloom.application.user.model.response.AdminItemResponse;
import java.util.Optional;
import org.springframework.data.domain.Page;

public interface AdminQueryService {

  /**
   * 按邮箱查询管理员。
   *
   * @param email 管理员邮箱
   * @return 匹配的管理员；不存在时返回 empty
   */
  Optional<AdminEntity> findAdminByEmail(String email);

  /**
   * 分页查询管理员列表，支持按邮箱模糊筛选，并汇总每个管理员的角色。
   *
   * @param adminPageRequest 分页与筛选参数
   * @return 管理员分页结果
   */
  Page<AdminItemResponse> page(AdminPageRequest adminPageRequest);
}
