package io.lexiloom.application.user.repository;

import io.lexiloom.application.common.BaseRepository;
import io.lexiloom.application.user.model.entity.WorkSpaceEntity;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 团队仓储 */
@Repository
public interface WorkSpaceRepository extends BaseRepository<WorkSpaceEntity> {

  Optional<WorkSpaceEntity> findByCode(String code);

  boolean existsByCode(String code);

  java.util.List<WorkSpaceEntity> findByParentId(Long parentId);

  java.util.List<WorkSpaceEntity> findByParentIdAndChildAssocEnabledTrue(Long parentId);
}
