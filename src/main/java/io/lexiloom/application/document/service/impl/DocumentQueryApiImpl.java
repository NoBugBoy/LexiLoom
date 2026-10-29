package io.lexiloom.application.document.service.impl;

import io.lexiloom.application.document.api.DocumentQueryApi;
import io.lexiloom.application.document.api.dto.DocSourceFileDTO;
import io.lexiloom.application.document.api.dto.DocumentDTO;
import io.lexiloom.application.document.api.dto.WorkspaceDocStatsDTO;
import io.lexiloom.application.document.mapper.DocSourceFileMapper;
import io.lexiloom.application.document.mapper.DocumentMapper;
import io.lexiloom.application.document.model.entity.DocCategoryEntity;
import io.lexiloom.application.document.model.entity.DocumentEntity;
import io.lexiloom.application.document.parser.DocParseStatusEnum;
import io.lexiloom.application.document.repository.DocCategoryRepository;
import io.lexiloom.application.document.repository.DocCommentRepository;
import io.lexiloom.application.document.repository.DocSourceFileRepository;
import io.lexiloom.application.document.repository.DocumentRepository;
import io.lexiloom.application.document.api.dto.DocumentStateEnum;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 文档模块跨模块查询服务实现 (DocumentQueryApiImpl) */
@Slf4j
@Primary
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DocumentQueryApiImpl implements DocumentQueryApi {

  private final DocumentRepository documentRepository;
  private final DocSourceFileRepository docSourceFileRepository;
  private final DocCategoryRepository docCategoryRepository;
  private final DocCommentRepository docCommentRepository;
  private final DocumentMapper documentMapper;
  private final DocSourceFileMapper docSourceFileMapper;
  private final io.lexiloom.application.document.spacelink.repository.WorkSpaceSpaceLinkRepository
      spaceLinkRepository;
  private final io.lexiloom.application.document.spacelink.repository
          .WorkSpaceSpaceLinkDocRepository
      spaceLinkDocRepository;

  @Override
  public Optional<DocumentDTO> findDocumentById(Long documentId) {
    if (documentId == null) {
      return Optional.empty();
    }
    return documentRepository.findById(documentId).map(documentMapper::toDTO);
  }

  @Override
  public Optional<DocSourceFileDTO> findSourceFileById(Long sourceFileId) {
    if (sourceFileId == null) {
      return Optional.empty();
    }
    return docSourceFileRepository.findById(sourceFileId).map(docSourceFileMapper::toDTO);
  }

  @Override
  public Optional<DocSourceFileDTO> findSourceFileByDocumentId(Long documentId) {
    if (documentId == null) {
      return Optional.empty();
    }
    return documentRepository
        .findById(documentId)
        .map(DocumentEntity::getSourceFileId)
        .filter(java.util.Objects::nonNull)
        .flatMap(docSourceFileRepository::findById)
        .or(() -> docSourceFileRepository.findFirstByDocumentIdOrderByCreateTimeDesc(documentId))
        .map(docSourceFileMapper::toDTO);
  }

  @Override
  public WorkspaceDocStatsDTO getWorkspaceDocStats(Long workSpaceId) {
    if (workSpaceId == null) {
      return new WorkspaceDocStatsDTO(
          0L, 0L, 0L, 0L, 0L, 0, 0L, 0L, "0 B", 0.0, 0L, 0L, 0L, 0L, List.of(), List.of(),
          List.of(), List.of(), List.of());
    }

    // 1. 文档各状态数量统计
    long totalDocuments = documentRepository.countByWorkSpaceId(workSpaceId);
    long approvedDocuments =
        documentRepository.countByWorkSpaceIdAndStatus(workSpaceId, DocumentStateEnum.APPROVED);
    long pendingDocuments =
        documentRepository.countByWorkSpaceIdAndStatus(
            workSpaceId, DocumentStateEnum.PENDING_APPROVAL);
    long rejectedDocuments =
        documentRepository.countByWorkSpaceIdAndStatus(workSpaceId, DocumentStateEnum.REJECTED);

    // 2. 总字数与阅读量
    long totalWordCount = 0L;
    long totalReadCount = 0L;
    List<Object[]> wordReadSums = documentRepository.sumWordCountAndReadCount(workSpaceId);
    if (wordReadSums != null && !wordReadSums.isEmpty() && wordReadSums.get(0) != null) {
      Object[] row = wordReadSums.get(0);
      totalWordCount = row[0] != null ? ((Number) row[0]).longValue() : 0L;
      totalReadCount = row[1] != null ? ((Number) row[1]).longValue() : 0L;
    }
    int avgWordCount = totalDocuments > 0 ? (int) (totalWordCount / totalDocuments) : 0;

    // 3. 源文件与存储统计
    long totalSourceFiles = 0L;
    long totalStorageBytes = 0L;
    List<Object[]> sourceSums = docSourceFileRepository.countAndSumFileSize(workSpaceId);
    if (sourceSums != null && !sourceSums.isEmpty() && sourceSums.get(0) != null) {
      Object[] row = sourceSums.get(0);
      totalSourceFiles = row[0] != null ? ((Number) row[0]).longValue() : 0L;
      totalStorageBytes = row[1] != null ? ((Number) row[1]).longValue() : 0L;
    }
    String formattedStorage = formatStorage(totalStorageBytes);

    // 4. 解析成功率统计
    List<Object[]> parseStatusGroups = docSourceFileRepository.countGroupByParseStatus(workSpaceId);
    long successCount = 0L;
    long totalParsedFiles = 0L;
    if (parseStatusGroups != null) {
      for (Object[] row : parseStatusGroups) {
        if (row != null && row.length >= 2) {
          DocParseStatusEnum status = (DocParseStatusEnum) row[0];
          long count = row[1] != null ? ((Number) row[1]).longValue() : 0L;
          totalParsedFiles += count;
          if (status == DocParseStatusEnum.SUCCESS) {
            successCount += count;
          }
        }
      }
    }
    double parseSuccessRate =
        totalParsedFiles > 0
            ? Math.round(((double) successCount / totalParsedFiles * 100.0) * 10.0) / 10.0
            : 100.0;

    // 5. 分类数与互动统计
    long categoryCount = docCategoryRepository.countByWorkSpaceId(workSpaceId);
    long totalComments = docCommentRepository.countByWorkSpaceId(workSpaceId);
    Long likeSum = docCommentRepository.sumLikeCountByWorkSpaceId(workSpaceId);
    long totalCommentLikes = likeSum != null ? likeSum : 0L;

    // 6. 热门必读文档 Top 10
    List<DocumentEntity> hotEntities =
        documentRepository.findTop10ByWorkSpaceIdOrderByReadCountDesc(workSpaceId);
    List<WorkspaceDocStatsDTO.HotDocumentDTO> hotDocs =
        hotEntities == null
            ? List.of()
            : hotEntities.stream()
                .map(
                    d ->
                        new WorkspaceDocStatsDTO.HotDocumentDTO(
                            d.getId(),
                            d.getTitle(),
                            d.getSummary(),
                            d.getReadCount() != null ? d.getReadCount() : 0,
                            d.getWordCount() != null ? d.getWordCount() : 0,
                            d.getCreateBy(),
                            d.getUpdateTime()))
                .toList();

    // 7. 最新发布动态 Top 5
    List<DocumentEntity> recentEntities =
        documentRepository.findTop5ByWorkSpaceIdOrderByUpdateTimeDesc(workSpaceId);
    List<WorkspaceDocStatsDTO.RecentDocumentDTO> recentDocs =
        recentEntities == null
            ? List.of()
            : recentEntities.stream()
                .map(
                    d ->
                        new WorkspaceDocStatsDTO.RecentDocumentDTO(
                            d.getId(),
                            d.getTitle(),
                            d.getCurrentVersion(),
                            d.getCurrentVersionTag(),
                            d.getStatus(),
                            d.getCreateBy(),
                            d.getUpdateTime()))
                .toList();

    // 8. 卓越作者 Top 5
    List<Object[]> topAuthorRows =
        documentRepository.findTopAuthors(workSpaceId, PageRequest.of(0, 5));
    List<WorkspaceDocStatsDTO.TopAuthorDTO> topAuthors =
        topAuthorRows == null
            ? List.of()
            : topAuthorRows.stream()
                .map(
                    row ->
                        new WorkspaceDocStatsDTO.TopAuthorDTO(
                            row[0] != null ? ((Number) row[0]).longValue() : 0L,
                            row[1] != null ? ((Number) row[1]).longValue() : 0L,
                            row[2] != null ? ((Number) row[2]).longValue() : 0L))
                .toList();

    // 9. 分类文档数量分布
    List<Object[]> catGroups = documentRepository.countGroupByCategoryId(workSpaceId);
    List<WorkspaceDocStatsDTO.CategoryDistributionDTO> catDistribution = new ArrayList<>();
    if (catGroups != null) {
      Set<Long> catIds =
          catGroups.stream()
              .filter(r -> r != null && r[0] != null)
              .map(r -> ((Number) r[0]).longValue())
              .collect(Collectors.toSet());
      Map<Long, String> catNameMap =
          docCategoryRepository.findAllById(catIds).stream()
              .collect(
                  Collectors.toMap(
                      DocCategoryEntity::getId, DocCategoryEntity::getName, (a, b) -> a));
      for (Object[] r : catGroups) {
        if (r != null) {
          Long catId = r[0] != null ? ((Number) r[0]).longValue() : null;
          long count = r[1] != null ? ((Number) r[1]).longValue() : 0L;
          String name = catId != null ? catNameMap.getOrDefault(catId, "未命名分类") : "未分类";
          catDistribution.add(new WorkspaceDocStatsDTO.CategoryDistributionDTO(catId, name, count));
        }
      }
    }

    // 10. 文件扩展名分布
    List<Object[]> fileExtGroups = docSourceFileRepository.countGroupByFileExt(workSpaceId);
    List<WorkspaceDocStatsDTO.FileTypeDistributionDTO> fileDistribution =
        fileExtGroups == null
            ? List.of()
            : fileExtGroups.stream()
                .filter(r -> r != null && r[0] != null)
                .map(
                    r ->
                        new WorkspaceDocStatsDTO.FileTypeDistributionDTO(
                            String.valueOf(r[0]), r[1] != null ? ((Number) r[1]).longValue() : 0L))
                .toList();

    return new WorkspaceDocStatsDTO(
        totalDocuments,
        approvedDocuments,
        pendingDocuments,
        rejectedDocuments,
        totalWordCount,
        avgWordCount,
        totalSourceFiles,
        totalStorageBytes,
        formattedStorage,
        parseSuccessRate,
        categoryCount,
        totalComments,
        totalCommentLikes,
        totalReadCount,
        hotDocs,
        recentDocs,
        topAuthors,
        catDistribution,
        fileDistribution);
  }

  @Override
  public List<io.lexiloom.application.document.api.dto.LinkableDocumentDTO> listLinkableDocuments(
      Long targetWorkspaceId, String keyword) {
    if (targetWorkspaceId == null) {
      return Collections.emptyList();
    }
    List<io.lexiloom.application.enums.WorkSpaceAclEnum> visibilities =
        List.of(
            io.lexiloom.application.enums.WorkSpaceAclEnum.PUBLIC,
            io.lexiloom.application.enums.WorkSpaceAclEnum.INTERNAL);

    List<DocumentEntity> docs =
        documentRepository.findLinkableDocuments(
            targetWorkspaceId, DocumentStateEnum.APPROVED, visibilities, keyword);

    return docs.stream()
        .map(
            d ->
                new io.lexiloom.application.document.api.dto.LinkableDocumentDTO(
                    d.getId(),
                    d.getTitle(),
                    d.getCategoryId(),
                    d.getVisibility(),
                    d.getUpdateTime()))
        .toList();
  }

  @Override
  public List<Long> listApprovedLinkedWorkspaceIds(Long sourceWorkspaceId) {
    if (sourceWorkspaceId == null) {
      return Collections.emptyList();
    }
    return spaceLinkRepository
        .findBySourceWorkspaceIdAndStatus(
            sourceWorkspaceId, io.lexiloom.application.enums.SpaceLinkStatusEnum.APPROVED)
        .stream()
        .map(
            io.lexiloom.application.document.spacelink.model.entity.WorkSpaceSpaceLinkEntity
                ::getTargetWorkspaceId)
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  @Override
  public Map<Long, List<Long>> getApprovedLinkedWorkspaceDocIds(Long sourceWorkspaceId) {
    if (sourceWorkspaceId == null) {
      return Collections.emptyMap();
    }

    List<io.lexiloom.application.document.spacelink.model.entity.WorkSpaceSpaceLinkEntity>
        approvedLinks =
            spaceLinkRepository.findBySourceWorkspaceIdAndStatus(
                sourceWorkspaceId, io.lexiloom.application.enums.SpaceLinkStatusEnum.APPROVED);

    if (approvedLinks.isEmpty()) {
      return Collections.emptyMap();
    }

    List<Long> linkIds =
        approvedLinks.stream()
            .map(
                io.lexiloom.application.document.spacelink.model.entity.WorkSpaceSpaceLinkEntity
                    ::getId)
            .toList();
    List<io.lexiloom.application.document.spacelink.model.entity.WorkSpaceSpaceLinkDocEntity>
        docEntities = spaceLinkDocRepository.findByLinkIdIn(linkIds);

    Map<Long, List<Long>> result = new HashMap<>();
    for (io.lexiloom.application.document.spacelink.model.entity.WorkSpaceSpaceLinkEntity link :
        approvedLinks) {
      result.putIfAbsent(link.getTargetWorkspaceId(), new ArrayList<>());
    }
    for (io.lexiloom.application.document.spacelink.model.entity.WorkSpaceSpaceLinkDocEntity doc :
        docEntities) {
      result
          .computeIfAbsent(doc.getTargetWorkspaceId(), k -> new ArrayList<>())
          .add(doc.getDocumentId());
    }
    return result;
  }

  @Override
  public long countDocumentsByWorkspaceId(Long workSpaceId) {
    if (workSpaceId == null) {
      return 0L;
    }
    return documentRepository.countByWorkSpaceId(workSpaceId);
  }

  @Override
  public Map<Long, String> getCategoryNames(java.util.Collection<Long> categoryIds) {
    if (categoryIds == null || categoryIds.isEmpty()) {
      return Collections.emptyMap();
    }
    Set<Long> validIds =
        categoryIds.stream()
            .filter(java.util.Objects::nonNull)
            .filter(id -> id > 0)
            .collect(Collectors.toSet());
    if (validIds.isEmpty()) {
      return Collections.emptyMap();
    }
    return docCategoryRepository.findAllById(validIds).stream()
        .collect(
            Collectors.toMap(
                DocCategoryEntity::getId, DocCategoryEntity::getName, (a, b) -> a));
  }

  private static String formatStorage(long bytes) {
    if (bytes <= 0) {
      return "0 B";
    }
    if (bytes < 1024L) {
      return bytes + " B";
    } else if (bytes < 1024L * 1024L) {
      return String.format(Locale.US, "%.1f KB", (double) bytes / 1024.0);
    } else if (bytes < 1024L * 1024L * 1024L) {
      return String.format(Locale.US, "%.1f MB", (double) bytes / (1024.0 * 1024.0));
    } else {
      return String.format(Locale.US, "%.1f GB", (double) bytes / (1024.0 * 1024.0 * 1024.0));
    }
  }
}
