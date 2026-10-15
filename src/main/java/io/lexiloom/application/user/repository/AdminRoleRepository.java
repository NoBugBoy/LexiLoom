package io.lexiloom.application.user.repository;

import io.lexiloom.application.common.BaseRepository;
import io.lexiloom.application.enums.AdminRoleEnum;
import io.lexiloom.application.user.model.entity.AdminRoleEntity;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public interface AdminRoleRepository extends BaseRepository<AdminRoleEntity> {
  List<AdminRoleEntity> findByUserId(Long userId);

  boolean existsByRole(AdminRoleEnum role);

  boolean existsByUserIdAndRole(Long userId, AdminRoleEnum role);

  void deleteByUserId(Long userId);
}
