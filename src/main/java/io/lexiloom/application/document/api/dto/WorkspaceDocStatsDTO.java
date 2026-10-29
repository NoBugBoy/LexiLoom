package io.lexiloom.application.document.api.dto;

import io.lexiloom.application.document.api.dto.DocumentStateEnum;
import java.time.LocalDateTime;
import java.util.List;

/** 工作空间文档与知识资产聚合统计数据传输对象 (不可变 record) */
public record WorkspaceDocStatsDTO(
    long totalDocuments,
    long approvedDocuments,
    long pendingDocuments,
    long rejectedDocuments,
    long totalWordCount,
    int avgWordCount,
    long totalSourceFiles,
    long totalStorageBytes,
    String formattedStorage,
    double parseSuccessRate,
    long categoryCount,
    long totalComments,
    long totalCommentLikes,
    long totalReadCount,
    List<HotDocumentDTO> hotDocuments,
    List<RecentDocumentDTO> recentDocuments,
    List<TopAuthorDTO> topAuthors,
    List<CategoryDistributionDTO> categoryDistribution,
    List<FileTypeDistributionDTO> fileTypeDistribution) {

  public record HotDocumentDTO(
      Long id,
      String title,
      String summary,
      Integer readCount,
      Integer wordCount,
      Long createBy,
      LocalDateTime updateTime) {}

  public record RecentDocumentDTO(
      Long id,
      String title,
      Integer currentVersion,
      String currentVersionTag,
      DocumentStateEnum status,
      Long createBy,
      LocalDateTime updateTime) {}

  public record TopAuthorDTO(Long userId, long documentCount, long totalWordCount) {}

  public record CategoryDistributionDTO(Long categoryId, String categoryName, long docCount) {}

  public record FileTypeDistributionDTO(String fileExt, long count) {}
}
