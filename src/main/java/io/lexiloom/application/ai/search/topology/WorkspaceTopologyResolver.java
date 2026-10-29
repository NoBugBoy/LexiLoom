package io.lexiloom.application.ai.search.topology;

import io.lexiloom.application.ai.search.HybridSearchCommand;
import io.lexiloom.application.ai.search.domain.WorkspaceSource;
import io.lexiloom.application.document.api.DocumentQueryApi;
import io.lexiloom.application.user.api.WorkSpaceQueryService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** 工作空间拓扑关系与跨空间授权解析器 */
@Slf4j
@Component
public class WorkspaceTopologyResolver {

  private final WorkSpaceQueryService workSpaceQueryService;
  private final DocumentQueryApi documentQueryApi;

  @org.springframework.beans.factory.annotation.Autowired
  public WorkspaceTopologyResolver(
      @Nullable WorkSpaceQueryService workSpaceQueryService,
      @Nullable DocumentQueryApi documentQueryApi) {
    this.workSpaceQueryService = workSpaceQueryService;
    this.documentQueryApi = documentQueryApi;
  }

  public WorkspaceTopologyResolver(@Nullable WorkSpaceQueryService workSpaceQueryService) {
    this(workSpaceQueryService, null);
  }

  public WorkspaceTopologyResolver() {
    this(null, null);
  }

  /** 解析指定主空间包含的所有可用数据源 (主空间 + 直系子空间 + 横向授权空间) */
  public List<WorkspaceSource> resolveAvailableSources(Long primaryId) {
    List<WorkspaceSource> sources = new ArrayList<>();
    sources.add(new WorkspaceSource(primaryId, "PRIMARY", false, null));

    if (workSpaceQueryService != null) {
      try {
        List<Long> childIds = workSpaceQueryService.listChildWorkspaceIds(primaryId);
        if (childIds != null) {
          for (Long childId : childIds) {
            if (childId != null && !childId.equals(primaryId)) {
              sources.add(new WorkspaceSource(childId, "CHILD_AUTO", true, null));
            }
          }
        }
      } catch (Exception e) {
        log.warn("【HybridSearch】查询子工作空间异常: primaryId={}, error={}", primaryId, e.getMessage());
      }
    }

    if (documentQueryApi != null) {
      try {
        Map<Long, List<Long>> linkedDocMap =
            documentQueryApi.getApprovedLinkedWorkspaceDocIds(primaryId);
        if (linkedDocMap != null) {
          for (Map.Entry<Long, List<Long>> entry : linkedDocMap.entrySet()) {
            Long linkedId = entry.getKey();
            List<Long> docIds = entry.getValue();
            if (linkedId != null
                && !linkedId.equals(primaryId)
                && sources.stream().noneMatch(s -> s.workspaceId().equals(linkedId))) {
              sources.add(new WorkspaceSource(linkedId, "EXPLICIT", false, docIds));
            }
          }
        }
      } catch (Exception e) {
        log.warn(
            "【HybridSearch】查询横向关联空间及文档清单异常: primaryId={}, error={}", primaryId, e.getMessage());
      }
    }

    return List.copyOf(sources);
  }

  /** 判断当前检索命令是否满足多空间联合检索条件 */
  public boolean hasMultiSource(HybridSearchCommand command, List<WorkspaceSource> sources) {
    if (sources.size() <= 1 || command.weights() == null || command.weights().isEmpty()) {
      return false;
    }
    return command.weights().entrySet().stream()
        .anyMatch(
            e ->
                sources.stream().anyMatch(s -> s.workspaceId().equals(e.getKey()))
                    && e.getValue() != null
                    && e.getValue() > 0);
  }

  /** 获取空间名称映射 */
  public Map<Long, String> resolveWorkspaceNames(Set<Long> spaceIds) {
    if (workSpaceQueryService == null || spaceIds == null || spaceIds.isEmpty()) {
      return Map.of();
    }
    try {
      Map<Long, String> result = workSpaceQueryService.getWorkspaceNames(spaceIds);
      return result != null ? Map.copyOf(result) : Map.of();
    } catch (Exception e) {
      log.debug("【HybridSearch】获取工作空间名称映射异常忽略: {}", e.getMessage());
      return Map.of();
    }
  }
}
