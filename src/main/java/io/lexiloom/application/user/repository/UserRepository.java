package io.lexiloom.application.user.repository;

import io.lexiloom.application.common.BaseRepository;
import io.lexiloom.application.user.model.entity.UserEntity;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * @author yujian
 */
@Repository
public interface UserRepository extends BaseRepository<UserEntity> {
  Optional<UserEntity> findByOpenId(String openId);

  Optional<UserEntity> findByEmail(String email);
}
