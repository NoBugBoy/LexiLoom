package com.knowflow.application.user.repository;

import com.knowflow.application.common.BaseRepository;
import com.knowflow.application.user.model.entity.WorkSpaceEntity;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 团队仓储 */
@Repository
public interface WorkSpaceRepository extends BaseRepository<WorkSpaceEntity> {

  Optional<WorkSpaceEntity> findByCode(String code);

  boolean existsByCode(String code);
}
