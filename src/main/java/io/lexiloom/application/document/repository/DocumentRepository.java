package io.lexiloom.application.document.repository;

import io.lexiloom.application.common.BaseRepository;
import io.lexiloom.application.document.model.entity.DocumentEntity;
import io.lexiloom.application.document.api.dto.DocumentStateEnum;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 文档核心聚合仓储 */
@Repository
public interface DocumentRepository extends BaseRepository<DocumentEntity> {

  Optional<DocumentEntity> findByIdAndWorkSpaceId(Long id, Long workSpaceId);

  List<DocumentEntity> findByWorkSpaceIdAndCategoryId(Long workSpaceId, Long categoryId);

  List<DocumentEntity> findByWorkSpaceIdAndStatus(Long workSpaceId, DocumentStateEnum status);

  long countByWorkSpaceId(Long workSpaceId);

  long countByWorkSpaceIdAndCategoryId(Long workSpaceId, Long categoryId);

  @org.springframework.data.jpa.repository.Query(
      "SELECT d.categoryId, COUNT(d.id) FROM DocumentEntity d WHERE d.workSpaceId = :workSpaceId"
          + " GROUP BY d.categoryId")
  List<Object[]> countGroupByCategoryId(
      @org.springframework.data.repository.query.Param("workSpaceId") Long workSpaceId);

  long countByWorkSpaceIdAndStatus(Long workSpaceId, DocumentStateEnum status);

  @org.springframework.data.jpa.repository.Query(
      "SELECT COALESCE(SUM(d.wordCount), 0), COALESCE(SUM(d.readCount), 0) FROM DocumentEntity d"
          + " WHERE d.workSpaceId = :workSpaceId")
  List<Object[]> sumWordCountAndReadCount(
      @org.springframework.data.repository.query.Param("workSpaceId") Long workSpaceId);

  List<DocumentEntity> findTop10ByWorkSpaceIdOrderByReadCountDesc(Long workSpaceId);

  List<DocumentEntity> findTop5ByWorkSpaceIdOrderByUpdateTimeDesc(Long workSpaceId);

  @org.springframework.data.jpa.repository.Query(
      "SELECT d.createBy, COUNT(d.id), COALESCE(SUM(d.wordCount), 0) FROM DocumentEntity d WHERE"
          + " d.workSpaceId = :workSpaceId GROUP BY d.createBy ORDER BY COUNT(d.id) DESC")
  List<Object[]> findTopAuthors(
      @org.springframework.data.repository.query.Param("workSpaceId") Long workSpaceId,
      org.springframework.data.domain.Pageable pageable);

  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query(
      "UPDATE DocumentEntity d SET d.readCount = d.readCount + 1 WHERE d.id = :id")
  void incrementReadCount(@org.springframework.data.repository.query.Param("id") Long id);

  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query(
      "UPDATE DocumentEntity d SET d.embeddingTokens = d.embeddingTokens + :tokens WHERE d.id = :id")
  void incrementEmbeddingTokens(
      @org.springframework.data.repository.query.Param("id") Long id,
      @org.springframework.data.repository.query.Param("tokens") int tokens);

  @org.springframework.data.jpa.repository.Query(
      "SELECT d FROM DocumentEntity d WHERE d.workSpaceId = :workSpaceId "
          + "AND d.status = :status "
          + "AND d.visibility IN :visibilities "
          + "AND (:keyword IS NULL OR :keyword = '' OR d.title LIKE CONCAT('%', :keyword, '%')) "
          + "ORDER BY d.updateTime DESC")
  List<DocumentEntity> findLinkableDocuments(
      @org.springframework.data.repository.query.Param("workSpaceId") Long workSpaceId,
      @org.springframework.data.repository.query.Param("status") DocumentStateEnum status,
      @org.springframework.data.repository.query.Param("visibilities")
          java.util.Collection<io.lexiloom.application.enums.WorkSpaceAclEnum> visibilities,
      @org.springframework.data.repository.query.Param("keyword") String keyword);
}
