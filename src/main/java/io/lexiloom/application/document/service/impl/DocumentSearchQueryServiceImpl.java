package io.lexiloom.application.document.service.impl;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.common.LoginUserAuthentication;
import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.document.acl.DocAccessControlService;
import io.lexiloom.application.document.api.DocumentQueryApi;
import io.lexiloom.application.document.search.DocSearchDocument;
import io.lexiloom.application.document.search.DocSearchHighlightFormatter;
import io.lexiloom.application.document.search.DocSearchQueryBuilder;
import io.lexiloom.application.document.search.DocSearchSourceMeta;
import io.lexiloom.application.document.search.dto.DocSearchItemVO;
import io.lexiloom.application.document.search.dto.DocumentSearchRequest;
import io.lexiloom.application.document.search.dto.DocumentSearchResponse;
import io.lexiloom.application.document.service.DocumentSearchQueryService;
import io.lexiloom.application.document.service.SearchHistoryService;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.exception.ErrorCode;
import io.lexiloom.application.user.api.WorkSpaceQueryService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

/**
 * 文档全文检索查询服务实现
 *
 * <p>作为检索用例编排者，协调安全鉴权、多空间拓扑解析、ES 原生查询与高亮组装，并在检索后非阻塞异步归档历史。
 */
@Slf4j
@Service
public class DocumentSearchQueryServiceImpl implements DocumentSearchQueryService {

  private final ElasticsearchOperations elasticsearchOperations;
  private final SearchHistoryService searchHistoryService;
  private final WorkSpaceQueryService workSpaceQueryService;
  private final DocumentQueryApi documentQueryApi;
  private final DocAccessControlService docAccessControlService;

  @Autowired
  public DocumentSearchQueryServiceImpl(
      ElasticsearchOperations elasticsearchOperations,
      SearchHistoryService searchHistoryService,
      @Nullable WorkSpaceQueryService workSpaceQueryService,
      @Nullable DocumentQueryApi documentQueryApi,
      @Nullable DocAccessControlService docAccessControlService) {
    this.elasticsearchOperations = elasticsearchOperations;
    this.searchHistoryService = searchHistoryService;
    this.workSpaceQueryService = workSpaceQueryService;
    this.documentQueryApi = documentQueryApi;
    this.docAccessControlService = docAccessControlService;
  }

  public DocumentSearchQueryServiceImpl(
      ElasticsearchOperations elasticsearchOperations,
      SearchHistoryService searchHistoryService,
      @Nullable WorkSpaceQueryService workSpaceQueryService,
      @Nullable DocumentQueryApi documentQueryApi) {
    this(
        elasticsearchOperations,
        searchHistoryService,
        workSpaceQueryService,
        documentQueryApi,
        null);
  }

  /** 兼容 2 参数构造器 (供独立单测使用) */
  public DocumentSearchQueryServiceImpl(
      ElasticsearchOperations elasticsearchOperations, SearchHistoryService searchHistoryService) {
    this(elasticsearchOperations, searchHistoryService, null, null, null);
  }

  @Override
  public DocumentSearchResponse search(DocumentSearchRequest request) {
    if (request == null || StrUtil.isBlank(request.keyword()) || request.workspaceId() == null) {
      throw new BusinessException(ErrorCode.Common.BAD_REQUEST, "检索关键词与工作区ID不能为空");
    }

    try {
      Long primaryId = request.workspaceId();
      if (docAccessControlService != null) {
        Long userId =
            SecurityHolder.getLoginUser().map(LoginUserAuthentication::userId).orElse(null);
        docAccessControlService.assertCanReadWorkSpace(primaryId, userId);
      }

      // 1. 解析可用空间拓扑 (主空间 + 直系子空间 + SpaceLink审批授权)
      List<DocSearchSourceMeta> availableSources = resolveAvailableSources(primaryId);

      // 2. 判定是否开启多空间检索及提取活跃检索源
      boolean hasExplicitLinkedWeights =
          request.weights() != null
              && request.weights().entrySet().stream()
                  .anyMatch(
                      e ->
                          !e.getKey().equals(primaryId)
                              && availableSources.stream()
                                  .anyMatch(s -> s.workspaceId().equals(e.getKey()))
                              && e.getValue() != null
                              && e.getValue() > 0);

      boolean shouldSearchMultiWorkspace =
          availableSources.size() > 1
              && (Boolean.TRUE.equals(request.includeLinked()) || hasExplicitLinkedWeights);

      List<DocSearchSourceMeta> activeSources;
      Map<Long, Float> boostMap;

      if (shouldSearchMultiWorkspace) {
        activeSources = new ArrayList<>();
        activeSources.add(availableSources.getFirst()); // 主空间必选

        for (int i = 1; i < availableSources.size(); i++) {
          DocSearchSourceMeta s = availableSources.get(i);
          // SpaceLink 关联若无授权文档则跳过
          if ("SPACE_LINK".equals(s.sourceType())
              && (s.allowedDocIds() == null || s.allowedDocIds().isEmpty())) {
            continue;
          }
          // 若指定了显式 weights，仅查询权重 > 0 的空间
          if (hasExplicitLinkedWeights) {
            Integer w = request.weights().get(s.workspaceId());
            if (w == null || w <= 0) {
              continue;
            }
          }
          activeSources.add(s);
        }

        // 计算有效权重与 Boost 打分映射 (自适应默认参数)
        Map<Long, Integer> effectiveWeights =
            resolveEffectiveWeights(request, activeSources, primaryId);
        boostMap = calculateBoostMap(effectiveWeights, activeSources);

        log.info(
            "【DocumentSearch】开启多空间加权检索: 主空间={}, 活跃空间数={}, 权重分配={}, Boost映射={}",
            primaryId,
            activeSources.size(),
            effectiveWeights,
            boostMap);
      } else {
        activeSources = List.of(availableSources.getFirst());
        boostMap = Collections.emptyMap();
      }

      boolean isMultiSource = activeSources.size() > 1;

      // 3. 构建 ES NativeQuery 并执行检索
      NativeQuery query =
          DocSearchQueryBuilder.build(request, isMultiSource, activeSources, boostMap);
      SearchHits<DocSearchDocument> searchHits =
          elasticsearchOperations.search(query, DocSearchDocument.class);

      long total = searchHits.getTotalHits();

      // 4. 解析工作空间名称与来源类型映射
      Map<Long, String> spaceNames = fetchSpaceNames(activeSources);
      Map<Long, String> relationTypes =
          activeSources.stream()
              .collect(
                  Collectors.toMap(
                      DocSearchSourceMeta::workspaceId, DocSearchSourceMeta::sourceType, (k1, k2) -> k1));

      List<DocSearchItemVO> items =
          searchHits.getSearchHits().stream()
              .map(hit -> DocSearchHighlightFormatter.mapToItemVO(hit, spaceNames, relationTypes))
              .toList();

      // 5. 异步记录搜索历史 (非阻塞)
      recordSearchHistoryAsync(request);

      return DocumentSearchResponse.of(total, request.pageSize(), items);
    } catch (BusinessException be) {
      throw be;
    } catch (Exception ex) {
      log.error(
          "Elasticsearch 检索执行失败: keyword={}, workspaceId={}, error={}",
          request.keyword(),
          request.workspaceId(),
          ex.getMessage(),
          ex);
      throw new BusinessException(ErrorCode.Document.ES_SEARCH_FAILED, "全文检索服务异常，请稍后重试");
    }
  }

  /** 解析可用空间拓扑 (主空间 + 直系子空间 + SpaceLink 审批授权) */
  private List<DocSearchSourceMeta> resolveAvailableSources(Long primaryId) {
    List<DocSearchSourceMeta> sources = new ArrayList<>();
    // 1. 本主空间
    sources.add(new DocSearchSourceMeta(primaryId, "PRIMARY", false, null));

    // 2. 直系子空间 (向下聚合)
    if (workSpaceQueryService != null) {
      try {
        List<Long> childIds = workSpaceQueryService.listChildWorkspaceIds(primaryId);
        if (childIds != null) {
          for (Long childId : childIds) {
            if (childId != null && !childId.equals(primaryId)) {
              sources.add(new DocSearchSourceMeta(childId, "CHILD", true, null));
            }
          }
        }
      } catch (Exception e) {
        log.warn("【DocumentSearch】查询子空间拓扑异常忽略: primaryId={}, error={}", primaryId, e.getMessage());
      }
    }

    // 3. 审批通过的横向关联 (SpaceLink)
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
              sources.add(new DocSearchSourceMeta(linkedId, "SPACE_LINK", false, docIds));
            }
          }
        }
      } catch (Exception e) {
        log.warn(
            "【DocumentSearch】查询横向关联空间及文档清单异常忽略: primaryId={}, error={}", primaryId, e.getMessage());
      }
    }

    return sources;
  }

  /** 计算各空间的有效检索权重 (自适应适配默认参数) */
  private Map<Long, Integer> resolveEffectiveWeights(
      DocumentSearchRequest request, List<DocSearchSourceMeta> activeSources, Long primaryId) {
    Map<Long, Integer> requestWeights = request.weights();
    Map<Long, Integer> effectiveWeights = new HashMap<>();

    if (requestWeights != null && !requestWeights.isEmpty()) {
      for (DocSearchSourceMeta source : activeSources) {
        Integer w = requestWeights.get(source.workspaceId());
        if (w != null && w > 0) {
          effectiveWeights.put(source.workspaceId(), w);
        }
      }
    }

    // 适配默认参数：若未配置权重或总和<=0，主空间默认赋 80，其余各关联空间平分 20
    if (effectiveWeights.isEmpty()
        || effectiveWeights.values().stream().mapToInt(Integer::intValue).sum() <= 0) {
      effectiveWeights.put(primaryId, 80);
      int associatedCount = activeSources.size() - 1;
      if (associatedCount > 0) {
        int perAssociatedWeight = Math.max(1, 20 / associatedCount);
        for (DocSearchSourceMeta source : activeSources) {
          if (!source.workspaceId().equals(primaryId)) {
            effectiveWeights.put(source.workspaceId(), perAssociatedWeight);
          }
        }
      }
    } else if (!effectiveWeights.containsKey(primaryId) || effectiveWeights.get(primaryId) <= 0) {
      // 若仅配置了关联空间权重而主空间缺失，赋予主空间兜底权重 80
      effectiveWeights.put(primaryId, 80);
    }

    return effectiveWeights;
  }

  /**
   * 将各空间权重映射为 Elasticsearch 的打分 Boost 系数
   */
  private Map<Long, Float> calculateBoostMap(
      Map<Long, Integer> effectiveWeights, List<DocSearchSourceMeta> activeSources) {
    int sum = effectiveWeights.values().stream().mapToInt(Integer::intValue).sum();
    if (sum <= 0) {
      sum = 100;
    }

    Map<Long, Float> boostMap = new HashMap<>();
    for (DocSearchSourceMeta source : activeSources) {
      int weight = effectiveWeights.getOrDefault(source.workspaceId(), 1);
      float ratio = (float) weight / sum;
      float boost = 0.5f + 2.5f * ratio;
      boostMap.put(source.workspaceId(), Math.round(boost * 100.0f) / 100.0f);
    }

    return boostMap;
  }

  private Map<Long, String> fetchSpaceNames(List<DocSearchSourceMeta> activeSources) {
    if (workSpaceQueryService == null || activeSources.isEmpty()) {
      return Collections.emptyMap();
    }
    Set<Long> spaceIds =
        activeSources.stream().map(DocSearchSourceMeta::workspaceId).collect(Collectors.toSet());
    try {
      Map<Long, String> names = workSpaceQueryService.getWorkspaceNames(spaceIds);
      return names != null ? names : Collections.emptyMap();
    } catch (Exception ex) {
      log.warn("【DocumentSearch】批量获取工作空间名称异常忽略: {}", ex.getMessage());
      return Collections.emptyMap();
    }
  }

  private void recordSearchHistoryAsync(DocumentSearchRequest request) {
    try {
      Long userId = SecurityHolder.getLoginUser().map(LoginUserAuthentication::userId).orElse(null);
      if (userId != null && StrUtil.isNotBlank(request.keyword())) {
        searchHistoryService.record(userId, request.workspaceId(), request.keyword());
      }
    } catch (Exception ex) {
      log.warn("异步记录搜索历史失败 (非阻塞): {}", ex.getMessage());
    }
  }
}
