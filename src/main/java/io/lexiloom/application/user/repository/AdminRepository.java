package io.lexiloom.application.user.repository;

import io.lexiloom.application.common.BaseRepository;
import io.lexiloom.application.user.model.entity.AdminEntity;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public interface AdminRepository extends BaseRepository<AdminEntity> {

  Optional<AdminEntity> findByEmail(String email);
}
