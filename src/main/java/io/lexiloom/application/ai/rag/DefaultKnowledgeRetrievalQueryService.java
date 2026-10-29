package io.lexiloom.application.ai.rag;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.gateway.rerank.ScoredDocChunkVO;
import io.lexiloom.application.ai.model.search.DocChunkDocument;
import io.lexiloom.application.ai.search.HybridSearchCommand;
import io.lexiloom.application.ai.search.HybridSearchQueryService;
import io.lexiloom.application.common.LoginUserAuthentication;
import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.enums.WorkSpaceRoleEnum;
import io.lexiloom.application.user.api.WorkSpaceMemberQueryService;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * 知识库纯切片检索与重排查询服务默认实现 (DefaultKnowledgeRetrievalQueryService)
 *
 * <p>封装多空间拓扑解析、向量 Dense/BM25 混合召回与 CrossEncoder 重排打分，不调用 LLM。
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class DefaultKnowledgeRetrievalQueryService implements KnowledgeRetrievalQueryService {

  private final HybridSearchQueryService hybridSearchQueryService;
  private final AiProperties aiProperties;
  @Nullable private final WorkSpaceMemberQueryService workSpaceMemberQueryService;

  @Override
  public List<DocChunkDocument> retrieveChunks(
      Long workspaceId, String query, Integer topK, Double minScore) {
    return retrieveScoredChunks(workspaceId, query, topK, minScore).stream()
        .map(ScoredDocChunkVO::chunk)
        .filter(Objects::nonNull)
        .toList();
  }

  @Override
  public List<ScoredDocChunkVO> retrieveScoredChunks(
      Long workspaceId, String query, Integer topK, Double minScore) {
    if (workspaceId == null || StrUtil.isBlank(query)) {
      return List.of();
    }

    int requestedTopK = topK != null ? topK : aiProperties.getRag().getTopK();
    int minTopK = aiProperties.getRag().getMinTopK();
    int maxTopK = aiProperties.getRag().getMaxTopK();
    int clampedTopK = Math.clamp(requestedTopK, minTopK, maxTopK);

    double resolvedMinScore =
        minScore != null ? minScore : aiProperties.getRag().getSimilarityThreshold();

    Long currentUserId =
        SecurityHolder.getLoginUser().map(LoginUserAuthentication::userId).orElse(null);
    boolean isAdmin = isWorkspaceOrSystemAdmin(workspaceId, currentUserId);

    HybridSearchCommand command =
        new HybridSearchCommand(
            workspaceId,
            query.trim(),
            null,
            clampedTopK,
            resolvedMinScore,
            null,
            List.of(),
            null,
            currentUserId,
            isAdmin,
            null,
            null);

    return retrieveScoredChunks(command);
  }

  @Override
  public List<ScoredDocChunkVO> retrieveScoredChunks(HybridSearchCommand command) {
    if (command == null) {
      return List.of();
    }
    List<ScoredDocChunkVO> scoredChunks = hybridSearchQueryService.hybridSearch(command);
    long distinctWorkspaces =
        scoredChunks.stream()
            .map(s -> s.chunk() != null ? s.chunk().getWorkspaceId() : null)
            .filter(Objects::nonNull)
            .distinct()
            .count();
    if (distinctWorkspaces > 1) {
      log.info("【KnowledgeRetrieval】多空间知识召回完成: 本次检索跨空间聚合引用了 {} 个工作空间的文档切片", distinctWorkspaces);
    }
    return scoredChunks;
  }

  private boolean isWorkspaceOrSystemAdmin(Long workspaceId, Long userId) {
    if (userId == null) {
      return false;
    }
    boolean isSystemAdmin =
        SecurityHolder.getLoginUser()
            .map(u -> u.roles().contains("ADMIN") || u.roles().contains("SUPER_ADMIN"))
            .orElse(false);
    if (isSystemAdmin) {
      return true;
    }
    return workSpaceMemberQueryService != null
        && workSpaceMemberQueryService.hasRole(workspaceId, userId, WorkSpaceRoleEnum.ADMIN);
  }
}
