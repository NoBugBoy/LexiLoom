package com.knowflow.application.document.repository;

import com.knowflow.application.common.BaseRepository;
import com.knowflow.application.document.model.entity.DocVersionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 文档历史版本快照仓储 */
@Repository
public interface DocVersionRepository extends BaseRepository<DocVersionEntity> {

  Optional<DocVersionEntity> findByDocumentIdAndVersionNumber(
      Long documentId, Integer versionNumber);

  List<DocVersionEntity> findByDocumentIdOrderByVersionNumberDesc(Long documentId);

  Optional<DocVersionEntity> findTopByDocumentIdOrderByVersionNumberDesc(Long documentId);

  long countByDocumentId(Long documentId);
}
