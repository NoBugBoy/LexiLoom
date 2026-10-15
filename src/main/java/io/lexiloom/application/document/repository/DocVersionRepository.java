package io.lexiloom.application.document.repository;

import io.lexiloom.application.common.BaseRepository;
import io.lexiloom.application.document.model.entity.DocVersionEntity;
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
