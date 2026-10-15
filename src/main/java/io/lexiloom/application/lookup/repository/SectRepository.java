package io.lexiloom.application.lookup.repository;

import io.lexiloom.application.common.BaseRepository;
import io.lexiloom.application.lookup.model.entity.SectEntity;
import org.springframework.stereotype.Repository;

@Repository
public interface SectRepository extends BaseRepository<SectEntity> {
  boolean existsBySectName(String sectName);
}
