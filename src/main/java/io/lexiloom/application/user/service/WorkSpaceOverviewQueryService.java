package io.lexiloom.application.user.service;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.repository.DocChunkRepository;
import io.lexiloom.application.document.api.DocumentQueryApi;
import io.lexiloom.application.document.api.dto.WorkspaceDocStatsDTO;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
import io.lexiloom.application.enums.WorkSpaceRoleEnum;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.api.WorkSpaceMemberQueryService;
import io.lexiloom.application.user.model.entity.UserEntity;
import io.lexiloom.application.user.model.entity.WorkSpaceEntity;
import io.lexiloom.application.user.model.response.WorkSpaceOverviewResponse;
import io.lexiloom.application.user.repository.UserRepository;
import io.lexiloom.application.user.repository.WorkSpaceMemberRepository;
import io.lexiloom.application.user.repository.WorkSpaceRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 团队工作空间数据总览仪表盘查询服务 (CQRS - Query Side)
 *
 * <p>聚合空间元数据、文档资产全量漏斗、ES 向量切片分布、成员席位与卓越作者榜，并通过 Redis 实现秒级热点防抖缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkSpaceOverviewQueryService {

  private static final String OVERVIEW_CACHE_KEY = "ll:ws:overview:%d";
  private static final long CACHE_TTL_SECONDS = 180L; // 仪表盘缓存 3 分钟

  private final WorkSpaceRepository workSpaceRepository;
  private final WorkSpaceMemberRepository workSpaceMemberRepository;
  private final WorkSpaceMemberQueryService workSpaceMemberQueryService;
  private final UserRepository userRepository;
  private final DocumentQueryApi documentQueryApi;
  private final DocChunkRepository docChunkRepository;
  private final AiProperties aiProperties;
  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;

  /**
   * 获取团队空间全景数据总览
   *
   * @param workSpaceId 工作空间 ID
   * @param currentUserId 当前登录用户 ID
   * @return 总览聚合响应 VO
   */
  @Transactional(readOnly = true)
  public WorkSpaceOverviewResponse getOverview(Long workSpaceId, Long currentUserId) {
    if (workSpaceId == null) {
      throw BusinessException.badRequest("团队空间 ID 不能为空");
    }

    // 1. 团队存在性与权限拦截
    WorkSpaceEntity ws =
        workSpaceRepository
            .findById(workSpaceId)
            .orElseThrow(() -> BusinessException.badRequest("团队不存在: " + workSpaceId));

    if (ws.getVisibility() != WorkSpaceAclEnum.PUBLIC) {
      if (currentUserId == null
          || !workSpaceMemberQueryService.isMember(workSpaceId, currentUserId)) {
        throw BusinessException.forbidden("您不是该团队成员，无权访问数据总览");
      }
    }

    // 2. 检查 Redis 仪表盘缓存
    String cacheKey = String.format(OVERVIEW_CACHE_KEY, workSpaceId);
    try {
      String cachedJson = redisTemplate.opsForValue().get(cacheKey);
      if (StrUtil.isNotBlank(cachedJson)) {
        return objectMapper.readValue(cachedJson, WorkSpaceOverviewResponse.class);
      }
    } catch (Exception e) {
      log.warn(
          "【WorkSpaceOverview】读取 Redis 仪表盘缓存异常: wsId={}, error={}", workSpaceId, e.getMessage());
    }

    // 3. 查询文档模块公开 API 聚合统计
    WorkspaceDocStatsDTO docStats = documentQueryApi.getWorkspaceDocStats(workSpaceId);

    // 4. 成员席位与角色分布
    long currentMembers = workSpaceMemberRepository.countByWorkSpaceId(workSpaceId);
    int maxMembers =
        ws.getMaxMembers() != null ? ws.getMaxMembers() : WorkSpaceEntity.DEFAULT_MAX_MEMBERS;
    double usagePercentage =
        maxMembers > 0
            ? Math.min(
                100.0, Math.round(((double) currentMembers / maxMembers * 100) * 10.0) / 10.0)
            : 0.0;
    long ownerCount =
        workSpaceMemberRepository.countByWorkSpaceIdAndRole(workSpaceId, WorkSpaceRoleEnum.OWNER);
    long adminCount =
        workSpaceMemberRepository.countByWorkSpaceIdAndRole(workSpaceId, WorkSpaceRoleEnum.ADMIN);
    long memberCount =
        workSpaceMemberRepository.countByWorkSpaceIdAndRole(workSpaceId, WorkSpaceRoleEnum.MEMBER);
    long guestCount =
        workSpaceMemberRepository.countByWorkSpaceIdAndRole(workSpaceId, WorkSpaceRoleEnum.GUEST);

    WorkSpaceOverviewResponse.MemberStatsVO memberStatsVO =
        new WorkSpaceOverviewResponse.MemberStatsVO(
            currentMembers,
            maxMembers,
            usagePercentage,
            ownerCount,
            adminCount,
            memberCount,
            guestCount);

    // 5. AI 智脑检索指标 (ES 密集向量切片)
    long totalChunks = 0L;
    try {
      totalChunks = docChunkRepository.countByWorkspaceId(String.valueOf(workSpaceId));
    } catch (Exception e) {
      log.warn("【WorkSpaceOverview】查询 ES 空间切片数异常: wsId={}, error={}", workSpaceId, e.getMessage());
    }
    boolean aiEnabled = aiProperties != null && aiProperties.isEnabled();
    WorkSpaceOverviewResponse.AiStatsVO aiStatsVO =
        new WorkSpaceOverviewResponse.AiStatsVO(totalChunks, aiEnabled);

    // 6. 批量补齐作者/用户信息 (昵称、头像)
    Set<Long> userIds = new HashSet<>();
    if (ws.getOwnerId() != null) {
      userIds.add(ws.getOwnerId());
    }
    if (docStats.hotDocuments() != null) {
      docStats
          .hotDocuments()
          .forEach(
              d -> {
                if (d.createBy() != null) userIds.add(d.createBy());
              });
    }
    if (docStats.recentDocuments() != null) {
      docStats
          .recentDocuments()
          .forEach(
              d -> {
                if (d.createBy() != null) userIds.add(d.createBy());
              });
    }
    if (docStats.topAuthors() != null) {
      docStats
          .topAuthors()
          .forEach(
              a -> {
                if (a.userId() != null) userIds.add(a.userId());
              });
    }

    Map<Long, UserEntity> userMap =
        userRepository.findAllById(userIds).stream()
            .collect(Collectors.toMap(UserEntity::getId, u -> u, (u1, u2) -> u1));

    // 7. 组装榜单与动态
    List<WorkSpaceOverviewResponse.HotDocumentVO> hotDocVOs =
        docStats.hotDocuments() == null
            ? List.of()
            : docStats.hotDocuments().stream()
                .map(
                    d -> {
                      UserEntity u = userMap.get(d.createBy());
                      String authorName =
                          (u != null && StrUtil.isNotBlank(u.getUserName()))
                              ? u.getUserName()
                              : "用户 " + d.createBy();
                      String avatarUrl = u != null ? u.getAvatarUrl() : null;
                      return new WorkSpaceOverviewResponse.HotDocumentVO(
                          d.id(),
                          d.title(),
                          d.summary(),
                          d.readCount(),
                          d.wordCount(),
                          d.createBy(),
                          authorName,
                          avatarUrl,
                          d.updateTime());
                    })
                .toList();

    List<WorkSpaceOverviewResponse.TopContributorVO> contributorVOs =
        docStats.topAuthors() == null
            ? List.of()
            : docStats.topAuthors().stream()
                .map(
                    a -> {
                      UserEntity u = userMap.get(a.userId());
                      String authorName =
                          (u != null && StrUtil.isNotBlank(u.getUserName()))
                              ? u.getUserName()
                              : "用户 " + a.userId();
                      String avatarUrl = u != null ? u.getAvatarUrl() : null;
                      return new WorkSpaceOverviewResponse.TopContributorVO(
                          a.userId(), authorName, avatarUrl, a.documentCount(), a.totalWordCount());
                    })
                .toList();

    List<WorkSpaceOverviewResponse.RecentDocumentVO> recentDocVOs =
        docStats.recentDocuments() == null
            ? List.of()
            : docStats.recentDocuments().stream()
                .map(
                    d -> {
                      UserEntity u = userMap.get(d.createBy());
                      String authorName =
                          (u != null && StrUtil.isNotBlank(u.getUserName()))
                              ? u.getUserName()
                              : "用户 " + d.createBy();
                      return new WorkSpaceOverviewResponse.RecentDocumentVO(
                          d.id(),
                          d.title(),
                          d.currentVersion(),
                          d.currentVersionTag(),
                          d.status(),
                          d.createBy(),
                          authorName,
                          d.updateTime());
                    })
                .toList();

    List<WorkSpaceOverviewResponse.CategoryDistributionVO> catDistributionVOs =
        docStats.categoryDistribution() == null
            ? List.of()
            : docStats.categoryDistribution().stream()
                .map(
                    c ->
                        new WorkSpaceOverviewResponse.CategoryDistributionVO(
                            c.categoryId(), c.categoryName(), c.docCount()))
                .toList();

    List<WorkSpaceOverviewResponse.FileTypeDistributionVO> fileTypeDistributionVOs =
        docStats.fileTypeDistribution() == null
            ? List.of()
            : docStats.fileTypeDistribution().stream()
                .map(
                    f ->
                        new WorkSpaceOverviewResponse.FileTypeDistributionVO(
                            f.fileExt(), f.count()))
                .toList();

    WorkSpaceOverviewResponse.WorkSpaceMetaVO metaVO =
        new WorkSpaceOverviewResponse.WorkSpaceMetaVO(
            ws.getId(),
            ws.getName(),
            ws.getCode(),
            ws.getDescription(),
            ws.getAvatarUrl(),
            ws.getOwnerId(),
            ws.getMaxMembers(),
            ws.getStatus(),
            ws.getVisibility(),
            ws.getCreateTime());

    WorkSpaceOverviewResponse.AssetStatsVO assetStatsVO =
        new WorkSpaceOverviewResponse.AssetStatsVO(
            docStats.totalDocuments(),
            docStats.approvedDocuments(),
            docStats.pendingDocuments(),
            docStats.rejectedDocuments(),
            docStats.totalWordCount(),
            docStats.avgWordCount(),
            docStats.totalSourceFiles(),
            docStats.totalStorageBytes(),
            docStats.formattedStorage(),
            docStats.parseSuccessRate(),
            docStats.categoryCount());

    WorkSpaceOverviewResponse.InteractionStatsVO interactionStatsVO =
        new WorkSpaceOverviewResponse.InteractionStatsVO(
            docStats.totalReadCount(), docStats.totalComments(), docStats.totalCommentLikes());

    WorkSpaceOverviewResponse response =
        new WorkSpaceOverviewResponse(
            metaVO,
            assetStatsVO,
            aiStatsVO,
            memberStatsVO,
            interactionStatsVO,
            catDistributionVOs,
            fileTypeDistributionVOs,
            hotDocVOs,
            contributorVOs,
            recentDocVOs);

    // 8. 写入 Redis 缓存
    try {
      String json = objectMapper.writeValueAsString(response);
      redisTemplate.opsForValue().set(cacheKey, json, CACHE_TTL_SECONDS, TimeUnit.SECONDS);
    } catch (Exception e) {
      log.warn("【WorkSpaceOverview】写入 Redis 缓存失败: wsId={}, error={}", workSpaceId, e.getMessage());
    }

    return response;
  }
}
