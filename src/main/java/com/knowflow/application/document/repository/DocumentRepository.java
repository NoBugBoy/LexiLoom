package com.knowflow.application.document.repository;

import com.knowflow.application.common.BaseRepository;
import com.knowflow.application.document.model.entity.DocumentEntity;
import com.knowflow.application.document.statemachine.DocumentStateEnum;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/** 文档核心聚合仓储 */
@Repository
public interface DocumentRepository
    extends BaseRepository<DocumentEntity>, JpaSpecificationExecutor<DocumentEntity> {

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

  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query(
      "UPDATE DocumentEntity d SET d.readCount = d.readCount + 1 WHERE d.id = :id")
  void incrementReadCount(@org.springframework.data.repository.query.Param("id") Long id);

  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query(
      "UPDATE DocumentEntity d SET d.likeCount = d.likeCount + 1 WHERE d.id = :id")
  void incrementLikeCount(@org.springframework.data.repository.query.Param("id") Long id);
}
