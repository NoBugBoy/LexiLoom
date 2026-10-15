package io.lexiloom.application.document.repository;

import io.lexiloom.application.common.BaseRepository;
import io.lexiloom.application.document.model.entity.DocTagRelationEntity;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 文档标签关联仓储 */
@Repository
public interface DocTagRelationRepository extends BaseRepository<DocTagRelationEntity> {

  List<DocTagRelationEntity> findByDocumentId(Long documentId);

  List<DocTagRelationEntity> findByTagId(Long tagId);

  void deleteByDocumentId(Long documentId);

  void deleteByDocumentIdAndTagId(Long documentId, Long tagId);
}
