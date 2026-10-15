package io.lexiloom.application.document.repository;

import io.lexiloom.application.common.BaseRepository;
import io.lexiloom.application.document.model.entity.DocCommentEntity;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 文档协同评论仓储 */
@Repository
public interface DocCommentRepository extends BaseRepository<DocCommentEntity> {

  List<DocCommentEntity> findByDocumentIdAndParentIdOrderByCreateTimeAsc(
      Long documentId, Long parentId);

  List<DocCommentEntity> findByDocumentIdOrderByCreateTimeAsc(Long documentId);

  long countByDocumentId(Long documentId);

  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query(
      "UPDATE DocCommentEntity c SET c.delFlag = c.id WHERE c.documentId = :documentId AND"
          + " c.delFlag = 0")
  void softDeleteByDocumentId(
      @org.springframework.data.repository.query.Param("documentId") Long documentId);

  void deleteByDocumentId(Long documentId);

  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query(
      "UPDATE DocCommentEntity c SET c.likeCount = c.likeCount + 1 WHERE c.id = :id")
  void incrementLikeCount(@org.springframework.data.repository.query.Param("id") Long id);

  long countByWorkSpaceId(Long workSpaceId);

  @org.springframework.data.jpa.repository.Query(
      "SELECT COALESCE(SUM(c.likeCount), 0) FROM DocCommentEntity c WHERE c.workSpaceId ="
          + " :workSpaceId")
  Long sumLikeCountByWorkSpaceId(
      @org.springframework.data.repository.query.Param("workSpaceId") Long workSpaceId);
}
