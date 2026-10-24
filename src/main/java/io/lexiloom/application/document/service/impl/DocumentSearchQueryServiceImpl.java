package io.lexiloom.application.document.service.impl;

import cn.hutool.core.util.StrUtil;
import co.elastic.clients.elasticsearch._types.FieldValue;
import io.lexiloom.application.common.ErrorCode;
import io.lexiloom.application.common.LoginUserAuthentication;
import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.document.api.DocumentQueryApi;
import io.lexiloom.application.document.search.DocSearchDocument;
import io.lexiloom.application.document.search.dto.DocSearchItemVO;
import io.lexiloom.application.document.search.dto.DocSearchSortBy;
import io.lexiloom.application.document.search.dto.DocumentSearchRequest;
import io.lexiloom.application.document.search.dto.DocumentSearchResponse;
import io.lexiloom.application.document.service.DocumentSearchQueryService;
import io.lexiloom.application.document.service.SearchHistoryService;
import io.lexiloom.application.document.statemachine.DocumentStateEnum;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.api.WorkSpaceQueryService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.HighlightQuery;
import org.springframework.data.elasticsearch.core.query.highlight.Highlight;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightField;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightParameters;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * 文档全文检索查询服务实现
 *
 * <p>基于 Elasticsearch 8.x + IK 分词构建 NativeQuery，包含多字段打分、工作区多源安全合流、 权重与 Boost
 * 动态映射、高亮及全局分页，并在检索完成后非阻塞异步记录用户历史搜索。
 */
@Slf4j
@Service
public class DocumentSearchQueryServiceImpl implements DocumentSearchQueryService {

  private static final int MAX_SNIPPET_LENGTH = 200;

  private final ElasticsearchOperations elasticsearchOperations;
  private final SearchHistoryService searchHistoryService;
  private final WorkSpaceQueryService workSpaceQueryService;
  private final DocumentQueryApi documentQueryApi;

  @Autowired
  public DocumentSearchQueryServiceImpl(
      ElasticsearchOperations elasticsearchOperations,
      SearchHistoryService searchHistoryService,
      @Nullable WorkSpaceQueryService workSpaceQueryService,
      @Nullable DocumentQueryApi documentQueryApi) {
    this.elasticsearchOperations = elasticsearchOperations;
    this.searchHistoryService = searchHistoryService;
    this.workSpaceQueryService = workSpaceQueryService;
    this.documentQueryApi = documentQueryApi;
  }

  /** 兼容 2 参数构造器 (供独立单测使用) */
  public DocumentSearchQueryServiceImpl(
      ElasticsearchOperations elasticsearchOperations, SearchHistoryService searchHistoryService) {
    this(elasticsearchOperations, searchHistoryService, null, null);
  }

  @Override
  public DocumentSearchResponse search(DocumentSearchRequest request) {
    if (request == null || StrUtil.isBlank(request.keyword()) || request.workspaceId() == null) {
      throw new BusinessException(ErrorCode.Common.BAD_REQUEST, "检索关键词与工作区ID不能为空");
    }

    try {
      Long primaryId = request.workspaceId();

      // 1. 解析可用空间拓扑 (主空间 + 直系子空间 + SpaceLink审批授权)
      List<SourceMeta> availableSources = resolveAvailableSources(primaryId);

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

      List<SourceMeta> activeSources;
      Map<Long, Float> boostMap;

      if (shouldSearchMultiWorkspace) {
        activeSources = new ArrayList<>();
        activeSources.add(availableSources.get(0)); // 主空间必选

        for (int i = 1; i < availableSources.size(); i++) {
          SourceMeta s = availableSources.get(i);
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
        activeSources = List.of(availableSources.get(0));
        boostMap = Collections.emptyMap();
      }

      boolean isMultiSource = activeSources.size() > 1;

      // 3. 构建 ES NativeQuery 并执行检索
      NativeQuery query = buildNativeQuery(request, isMultiSource, activeSources, boostMap);
      SearchHits<DocSearchDocument> searchHits =
          elasticsearchOperations.search(query, DocSearchDocument.class);

      long total = searchHits.getTotalHits();

      // 4. 解析工作空间名称与来源类型映射
      Map<Long, String> spaceNames = fetchSpaceNames(activeSources);
      Map<Long, String> relationTypes =
          activeSources.stream()
              .collect(
                  Collectors.toMap(
                      SourceMeta::workspaceId, SourceMeta::sourceType, (k1, k2) -> k1));

      List<DocSearchItemVO> items =
          searchHits.getSearchHits().stream()
              .map(hit -> mapToItemVO(hit, spaceNames, relationTypes))
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

  private NativeQuery buildNativeQuery(
      DocumentSearchRequest request,
      boolean isMultiSource,
      List<SourceMeta> activeSources,
      Map<Long, Float> boostMap) {

    NativeQueryBuilder builder = NativeQuery.builder();

    // 1. Bool Query: must + filter + should (boost)
    builder.withQuery(
        q ->
            q.bool(
                b -> {
                  // Must: multi_match 跨多字段全文检索，设置不同字段检索打分权重
                  b.must(
                      m ->
                          m.multiMatch(
                              mm ->
                                  mm.query(request.keyword())
                                      .fields(
                                          List.of(
                                              "title^3", "summary^2", "content^1", "rawText^1"))));

                  // Filter 1: status = APPROVED (仅允许检索审批通过文档)
                  b.filter(
                      f -> f.term(t -> t.field("status").value(DocumentStateEnum.APPROVED.name())));

                  // Filter 2: categoryId (可选分类过滤)
                  if (request.categoryId() != null) {
                    b.filter(
                        f ->
                            f.term(
                                t ->
                                    t.field("categoryId")
                                        .value(String.valueOf(request.categoryId()))));
                  }

                  // Filter 3: tags (可选标签过滤)
                  if (request.tags() != null && !request.tags().isEmpty()) {
                    List<FieldValue> tagValues =
                        request.tags().stream().map(FieldValue::of).toList();
                    b.filter(f -> f.terms(t -> t.field("tags").terms(tq -> tq.value(tagValues))));
                  }

                  // Filter 4: 多空间租户安全隔离
                  if (!isMultiSource) {
                    // 单空间模式：直接 term 过滤主空间
                    b.filter(
                        f ->
                            f.term(
                                t ->
                                    t.field("workspaceId")
                                        .value(String.valueOf(request.workspaceId()))));
                  } else {
                    // 跨空间模式：复合 should + minimum_should_match=1 安全合流过滤
                    b.filter(
                        f ->
                            f.bool(
                                sb -> {
                                  sb.minimumShouldMatch("1");
                                  for (SourceMeta source : activeSources) {
                                    if (source.workspaceId().equals(request.workspaceId())) {
                                      // 本主空间文档：全量安全允许
                                      sb.should(
                                          sq ->
                                              sq.term(
                                                  st ->
                                                      st.field("workspaceId")
                                                          .value(
                                                              String.valueOf(
                                                                  source.workspaceId()))));
                                    } else if ("CHILD".equals(source.sourceType())) {
                                      // 直系子空间向下聚合：仅限 PUBLIC 与 INTERNAL 文档
                                      sb.should(
                                          sq ->
                                              sq.bool(
                                                  cb ->
                                                      cb.filter(
                                                              cf ->
                                                                  cf.term(
                                                                      ct ->
                                                                          ct.field("workspaceId")
                                                                              .value(
                                                                                  String.valueOf(
                                                                                      source
                                                                                          .workspaceId()))))
                                                          .filter(
                                                              cf ->
                                                                  cf.terms(
                                                                      ct ->
                                                                          ct.field("visibility")
                                                                              .terms(
                                                                                  tq ->
                                                                                      tq.value(
                                                                                          List.of(
                                                                                              FieldValue
                                                                                                  .of(
                                                                                                      "PUBLIC"),
                                                                                              FieldValue
                                                                                                  .of(
                                                                                                      "INTERNAL"))))))));
                                    } else if ("SPACE_LINK".equals(source.sourceType())) {
                                      // SpaceLink 横向关联：仅限细粒度审批通过的具体文档白名单
                                      if (source.allowedDocIds() != null
                                          && !source.allowedDocIds().isEmpty()) {
                                        List<FieldValue> docIdValues =
                                            source.allowedDocIds().stream()
                                                .map(docId -> FieldValue.of(String.valueOf(docId)))
                                                .toList();
                                        sb.should(
                                            sq ->
                                                sq.bool(
                                                    lb ->
                                                        lb.filter(
                                                                lf ->
                                                                    lf.term(
                                                                        lt ->
                                                                            lt.field("workspaceId")
                                                                                .value(
                                                                                    String.valueOf(
                                                                                        source
                                                                                            .workspaceId()))))
                                                            .filter(
                                                                lf ->
                                                                    lf.terms(
                                                                        lt ->
                                                                            lt.field("id")
                                                                                .terms(
                                                                                    tq ->
                                                                                        tq.value(
                                                                                            docIdValues))))));
                                      }
                                    }
                                  }
                                  return sb;
                                }));

                    // 空间权重 Boost 打分倾斜：通过 should 给高权重空间提供相关性加权
                    if (boostMap != null && !boostMap.isEmpty()) {
                      for (SourceMeta source : activeSources) {
                        float boost = boostMap.getOrDefault(source.workspaceId(), 1.0f);
                        b.should(
                            sq ->
                                sq.term(
                                    st ->
                                        st.field("workspaceId")
                                            .value(String.valueOf(source.workspaceId()))
                                            .boost(boost)));
                      }
                    }
                  }

                  return b;
                }));

    // 2. Highlight: 高亮配置
    HighlightParameters highlightParameters =
        HighlightParameters.builder()
            .withPreTags("<em class=\"hl\">")
            .withPostTags("</em>")
            .withNumberOfFragments(1)
            .withFragmentSize(MAX_SNIPPET_LENGTH)
            .build();

    List<HighlightField> highlightFields =
        List.of(
            new HighlightField("title"),
            new HighlightField("summary"),
            new HighlightField("content"),
            new HighlightField("rawText"));

    Highlight highlight = new Highlight(highlightParameters, highlightFields);
    builder.withHighlightQuery(new HighlightQuery(highlight, DocSearchDocument.class));

    // 3. Sort: 相关性降序 或 发布时间倒序
    Sort sort =
        request.sortBy() == DocSearchSortBy.PUBLISHED_AT
            ? Sort.by(Sort.Direction.DESC, "publishedAt")
            : Sort.by(Sort.Direction.DESC, "_score");

    // 4. Pagination & 精确总命中数
    Pageable pageable = PageRequest.of(request.pageNum() - 1, request.pageSize(), sort);
    builder.withPageable(pageable);
    builder.withTrackTotalHits(true);

    return builder.build();
  }

  /** 解析可用空间拓扑 (主空间 + 直系子空间 + SpaceLink 审批授权) */
  private List<SourceMeta> resolveAvailableSources(Long primaryId) {
    List<SourceMeta> sources = new ArrayList<>();
    // 1. 本主空间
    sources.add(new SourceMeta(primaryId, "PRIMARY", false, null));

    // 2. 直系子空间 (向下聚合)
    if (workSpaceQueryService != null) {
      try {
        List<Long> childIds = workSpaceQueryService.listChildWorkspaceIds(primaryId);
        if (childIds != null) {
          for (Long childId : childIds) {
            if (childId != null && !childId.equals(primaryId)) {
              sources.add(new SourceMeta(childId, "CHILD", true, null));
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
              sources.add(new SourceMeta(linkedId, "SPACE_LINK", false, docIds));
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
      DocumentSearchRequest request, List<SourceMeta> activeSources, Long primaryId) {
    Map<Long, Integer> requestWeights = request.weights();
    Map<Long, Integer> effectiveWeights = new HashMap<>();

    if (requestWeights != null && !requestWeights.isEmpty()) {
      for (SourceMeta source : activeSources) {
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
        for (SourceMeta source : activeSources) {
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
   *
   * <p>映射公式：设各空间占比 r_i = w_i / sum(weights)，boost_i = 0.5f + 2.5f * r_i 例如：主空间 80% 占比 -> boost =
   * 2.5f；关联空间 20% 占比 -> boost = 1.0f (与 ES 默认权重 1.0 对齐)
   */
  private Map<Long, Float> calculateBoostMap(
      Map<Long, Integer> effectiveWeights, List<SourceMeta> activeSources) {
    int sum = effectiveWeights.values().stream().mapToInt(Integer::intValue).sum();
    if (sum <= 0) {
      sum = 100;
    }

    Map<Long, Float> boostMap = new HashMap<>();
    for (SourceMeta source : activeSources) {
      int weight = effectiveWeights.getOrDefault(source.workspaceId(), 1);
      float ratio = (float) weight / sum;
      float boost = 0.5f + 2.5f * ratio;
      boostMap.put(source.workspaceId(), Math.round(boost * 100.0f) / 100.0f);
    }

    return boostMap;
  }

  private Map<Long, String> fetchSpaceNames(List<SourceMeta> activeSources) {
    if (workSpaceQueryService == null || activeSources.isEmpty()) {
      return Collections.emptyMap();
    }
    Set<Long> spaceIds =
        activeSources.stream().map(SourceMeta::workspaceId).collect(Collectors.toSet());
    try {
      Map<Long, String> names = workSpaceQueryService.getWorkspaceNames(spaceIds);
      return names != null ? names : Collections.emptyMap();
    } catch (Exception ex) {
      log.warn("【DocumentSearch】批量获取工作空间名称异常忽略: {}", ex.getMessage());
      return Collections.emptyMap();
    }
  }

  private DocSearchItemVO mapToItemVO(
      SearchHit<DocSearchDocument> hit,
      Map<Long, String> spaceNames,
      Map<Long, String> relationTypes) {
    DocSearchDocument doc = hit.getContent();
    Map<String, List<String>> hlMap = hit.getHighlightFields();

    String title = extractFirstHighlight(hlMap, "title", doc.getTitle());
    String summary = extractFirstHighlight(hlMap, "summary", doc.getSummary());
    String hitSnippet = extractHitSnippet(hlMap, doc);

    Long wsId = null;
    if (doc.getWorkspaceId() != null) {
      try {
        wsId = Long.parseLong(doc.getWorkspaceId());
      } catch (NumberFormatException ignored) {
      }
    }

    String wsName = wsId != null ? spaceNames.getOrDefault(wsId, "工作空间#" + wsId) : null;
    String relationType = wsId != null ? relationTypes.getOrDefault(wsId, "PRIMARY") : "PRIMARY";

    return new DocSearchItemVO(
        doc.getId(),
        title,
        summary,
        hitSnippet,
        doc.getCategoryId(),
        doc.getTags(),
        doc.getSourceType(),
        doc.getPublishedAt(),
        wsId,
        wsName,
        relationType);
  }

  private String extractHitSnippet(Map<String, List<String>> hlMap, DocSearchDocument doc) {
    List<String> rawTextHl = hlMap.get("rawText");
    if (rawTextHl != null && !rawTextHl.isEmpty()) {
      return safeTruncateHtml(String.join(" ... ", rawTextHl), MAX_SNIPPET_LENGTH);
    }

    List<String> contentHl = hlMap.get("content");
    if (contentHl != null && !contentHl.isEmpty()) {
      return safeTruncateHtml(String.join(" ... ", contentHl), MAX_SNIPPET_LENGTH);
    }

    if (StrUtil.isNotBlank(doc.getSummary())) {
      return safeTruncateHtml(doc.getSummary(), MAX_SNIPPET_LENGTH);
    }

    if (StrUtil.isNotBlank(doc.getRawText())) {
      return safeTruncateHtml(doc.getRawText(), MAX_SNIPPET_LENGTH);
    }

    if (StrUtil.isNotBlank(doc.getContent())) {
      return safeTruncateHtml(doc.getContent(), MAX_SNIPPET_LENGTH);
    }

    return "";
  }

  private String extractFirstHighlight(
      Map<String, List<String>> hlMap, String field, String defaultValue) {
    List<String> fragments = hlMap.get(field);
    if (fragments != null && !fragments.isEmpty()) {
      return String.join("...", fragments);
    }
    return defaultValue != null ? defaultValue : "";
  }

  private String safeTruncateHtml(String text, int maxLength) {
    if (text == null) {
      return "";
    }
    if (text.length() <= maxLength) {
      return text;
    }

    String truncated = text.substring(0, maxLength);

    int lastOpenBracket = truncated.lastIndexOf('<');
    int lastCloseBracket = truncated.lastIndexOf('>');
    if (lastOpenBracket > lastCloseBracket) {
      truncated = truncated.substring(0, lastOpenBracket);
    }

    int openCount = StrUtil.count(truncated, "<em class=\"hl\">");
    int closeCount = StrUtil.count(truncated, "</em>");

    StringBuilder sb = new StringBuilder(truncated).append("...");
    for (int i = 0; i < openCount - closeCount; i++) {
      sb.append("</em>");
    }

    return sb.toString();
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

  private record SourceMeta(
      Long workspaceId, String sourceType, boolean onlyPublic, List<Long> allowedDocIds) {}
}
