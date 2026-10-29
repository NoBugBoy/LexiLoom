package io.lexiloom.application.overview.model.response;

import cn.hutool.core.date.DatePattern;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.lexiloom.application.document.api.dto.DocumentStateEnum;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
import io.lexiloom.application.enums.WorkSpaceStatusEnum;
import java.time.LocalDateTime;
import java.util.List;

/** 团队工作空间数据总览仪表盘响应 VO (不可变 record) */
public record WorkSpaceOverviewResponse(
    // 1. 团队基本元数据
    WorkSpaceMetaVO workspace,
    // 2. 核心资产统计看板 (文档状态漏斗、字数、文件与存储)
    AssetStatsVO assets,
    // 3. AI 智脑检索中枢指标 (ES 向量切片、问答活跃度)
    AiStatsVO ai,
    // 4. 成员与席位配额 (人数、配额使用率、角色分布)
    MemberStatsVO members,
    // 5. 知识流通与互动 (阅读、评论、点赞)
    InteractionStatsVO interaction,
    // 6. 分类覆盖分布
    List<CategoryDistributionVO> categoryDistribution,
    // 7. 原始文件格式分布
    List<FileTypeDistributionVO> fileTypeDistribution,
    // 8. 空间热门必读文档 Top 10
    List<HotDocumentVO> hotDocuments,
    // 9. 团队作者卓越贡献榜 Top 5
    List<TopContributorVO> topContributors,
    // 10. 最近发布与更新动态 Top 5
    List<RecentDocumentVO> recentDocuments) {

  public record WorkSpaceMetaVO(
      Long id,
      String name,
      String code,
      String description,
      String avatarUrl,
      Long ownerId,
      Integer maxMembers,
      WorkSpaceStatusEnum status,
      WorkSpaceAclEnum visibility,
      @JsonFormat(pattern = DatePattern.NORM_DATETIME_PATTERN) LocalDateTime createTime) {}

  public record AssetStatsVO(
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
      long categoryCount) {}

  public record AiStatsVO(long totalChunks, boolean aiEnabled) {}

  public record MemberStatsVO(
      long currentMembers,
      int maxMembers,
      double usagePercentage,
      long ownerCount,
      long adminCount,
      long memberCount,
      long guestCount) {}

  public record InteractionStatsVO(
      long totalReadCount, long totalComments, long totalCommentLikes) {}

  public record CategoryDistributionVO(Long categoryId, String categoryName, long docCount) {}

  public record FileTypeDistributionVO(String fileExt, long count) {}

  public record HotDocumentVO(
      Long id,
      String title,
      String summary,
      Integer readCount,
      Integer wordCount,
      Long createBy,
      String authorName,
      String authorAvatar,
      @JsonFormat(pattern = DatePattern.NORM_DATETIME_PATTERN) LocalDateTime updateTime) {}

  public record TopContributorVO(
      Long userId, String userName, String avatarUrl, long documentCount, long totalWordCount) {}

  public record RecentDocumentVO(
      Long id,
      String title,
      Integer currentVersion,
      String currentVersionTag,
      DocumentStateEnum status,
      Long createBy,
      String authorName,
      @JsonFormat(pattern = DatePattern.NORM_DATETIME_PATTERN) LocalDateTime updateTime) {}
}
