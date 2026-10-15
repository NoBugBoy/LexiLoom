package io.lexiloom.application.document.repository;

import io.lexiloom.application.common.BaseRepository;
import io.lexiloom.application.document.model.entity.DocTagEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 文档标签仓储 */
@Repository
public interface DocTagRepository extends BaseRepository<DocTagEntity> {

  Optional<DocTagEntity> findByWorkSpaceIdAndName(Long workSpaceId, String name);

  List<DocTagEntity> findByWorkSpaceId(Long workSpaceId);

  boolean existsByWorkSpaceIdAndName(Long workSpaceId, String name);
}
