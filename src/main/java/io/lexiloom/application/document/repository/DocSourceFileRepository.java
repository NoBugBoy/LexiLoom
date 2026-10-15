package io.lexiloom.application.document.repository;

import io.lexiloom.application.common.BaseRepository;
import io.lexiloom.application.document.model.entity.DocSourceFileEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 来源原始文件资产仓储 */
@Repository
public interface DocSourceFileRepository extends BaseRepository<DocSourceFileEntity> {

  Optional<DocSourceFileEntity> findFirstByFileHashOrderByCreateTimeDesc(String fileHash);

  Optional<DocSourceFileEntity> findFirstByWorkSpaceIdAndFileHashOrderByCreateTimeDesc(
      Long workSpaceId, String fileHash);

  Optional<DocSourceFileEntity> findFirstByWorkSpaceIdAndOriginalFileNameOrderByCreateTimeDesc(
      Long workSpaceId, String originalFileName);

  Optional<DocSourceFileEntity> findByFileHash(String fileHash);

  Optional<DocSourceFileEntity> findByWorkSpaceIdAndFileHash(Long workSpaceId, String fileHash);

  Optional<DocSourceFileEntity> findByDocumentId(Long documentId);

  List<DocSourceFileEntity> findByWorkSpaceId(Long workSpaceId);

  boolean existsByWorkSpaceIdAndFileHash(Long workSpaceId, String fileHash);

  @org.springframework.data.jpa.repository.Query(
      "SELECT COUNT(f.id), COALESCE(SUM(f.fileSize), 0) FROM DocSourceFileEntity f WHERE"
          + " f.workSpaceId = :workSpaceId")
  List<Object[]> countAndSumFileSize(
      @org.springframework.data.repository.query.Param("workSpaceId") Long workSpaceId);

  @org.springframework.data.jpa.repository.Query(
      "SELECT f.fileExt, COUNT(f.id) FROM DocSourceFileEntity f WHERE f.workSpaceId = :workSpaceId"
          + " GROUP BY f.fileExt")
  List<Object[]> countGroupByFileExt(
      @org.springframework.data.repository.query.Param("workSpaceId") Long workSpaceId);

  @org.springframework.data.jpa.repository.Query(
      "SELECT f.parseStatus, COUNT(f.id) FROM DocSourceFileEntity f WHERE f.workSpaceId ="
          + " :workSpaceId GROUP BY f.parseStatus")
  List<Object[]> countGroupByParseStatus(
      @org.springframework.data.repository.query.Param("workSpaceId") Long workSpaceId);
}
