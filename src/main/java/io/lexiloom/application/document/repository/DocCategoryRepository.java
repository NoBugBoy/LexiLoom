package io.lexiloom.application.document.repository;

import io.lexiloom.application.common.BaseRepository;
import io.lexiloom.application.document.model.entity.DocCategoryEntity;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 分类目录树仓储 */
@Repository
public interface DocCategoryRepository extends BaseRepository<DocCategoryEntity> {

  List<DocCategoryEntity> findByWorkSpaceIdOrderBySortOrderAsc(Long workSpaceId);

  List<DocCategoryEntity> findByWorkSpaceIdAndParentIdOrderBySortOrderAsc(
      Long workSpaceId, Long parentId);

  boolean existsByWorkSpaceIdAndParentIdAndName(Long workSpaceId, Long parentId, String name);

  /** 校验分类目录是否归属指定工作空间（跨租户挂载防护） */
  boolean existsByIdAndWorkSpaceId(Long id, Long workSpaceId);

  List<DocCategoryEntity> findByWorkSpaceIdAndPathStartingWith(Long workSpaceId, String pathPrefix);
}
