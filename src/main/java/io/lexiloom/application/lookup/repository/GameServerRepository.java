package io.lexiloom.application.lookup.repository;

import io.lexiloom.application.common.BaseRepository;
import io.lexiloom.application.lookup.model.entity.GameServerEntity;
import org.springframework.stereotype.Repository;

@Repository
public interface GameServerRepository extends BaseRepository<GameServerEntity> {
  boolean existsByServerName(String serverName);
}
