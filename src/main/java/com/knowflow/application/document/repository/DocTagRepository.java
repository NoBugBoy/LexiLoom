package com.knowflow.application.document.repository;

import com.knowflow.application.common.BaseRepository;
import com.knowflow.application.document.model.entity.DocTagEntity;
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
