package io.lexiloom.application.ai.search;

import cn.hutool.core.util.StrUtil;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.enums.RerankStrategyEnum;
import io.lexiloom.application.ai.gateway.embedding.EmbeddingGateway;
import io.lexiloom.application.ai.gateway.rerank.RerankCommand;
import io.lexiloom.application.ai.gateway.rerank.RerankGateway;
import io.lexiloom.application.ai.gateway.rerank.ScoredDocChunkVO;
import io.lexiloom.application.ai.model.search.DocChunkDocument;
import io.lexiloom.application.user.api.WorkSpaceQueryService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * 双路混合召回与精排服务默认实现 (DefaultHybridSearchQueryService)
 *
 * <p>核心流程：
 *
 * <ol>
 *   <li>问题向量化：基于 {@link EmbeddingGateway} 获取 1536 维 dense vector；
 *   <li>并行检索：
 *       <ul>
 *         <li>路径 1 (词法全文检索)：基于 IK 分词的 multi_match (content^3, headerPath^1.5, title^1,
 *             contextPrefix^0.5，best_fields + tie_breaker)，正文主导打分、文档级冗余字段降权以防同文档并列刷屏；
 *         <li>路径 2 (稠密向量检索)：基于 Elasticsearch 8.x kNN 检索 content_vector 向量相似度；
 *       </ul>
 *   <li>多空间拓扑与安全围栏：主空间检索全量权限切片，直系子空间/横向空间强制仅检索 PUBLIC 切片；
 *   <li>加权配额与余量补偿：前端实时传递 weights 比例分发召回名额，缺额空间自适应回补给主空间；
 *   <li>精排融合：委托 {@link RerankGateway}（NoOp / RRF / CrossEncoder）执行打分排序；
 *   <li>终态选择：真实向量余弦回填 + 相似度阈值过滤 + 文档级多样性上限 + Top-K 截取。
 * </ol>
 */
@Slf4j
@Service
@Primary
public class DefaultHybridSearchQueryService implements HybridSearchQueryService {

  private final ElasticsearchOperations elasticsearchOperations;
  private final EmbeddingGateway embeddingGateway;
  private final RerankGateway rerankGateway;
  private final AiProperties aiProperties;
  private final WorkSpaceQueryService workSpaceQueryService;
  private final io.lexiloom.application.document.api.DocumentQueryApi documentQueryApi;

  @Autowired
  public DefaultHybridSearchQueryService(
      ElasticsearchOperations elasticsearchOperations,
      EmbeddingGateway embeddingGateway,
      RerankGateway rerankGateway,
      AiProperties aiProperties,
      @Nullable WorkSpaceQueryService workSpaceQueryService,
      @Nullable io.lexiloom.application.document.api.DocumentQueryApi documentQueryApi) {
    this.elasticsearchOperations = elasticsearchOperations;
    this.embeddingGateway = embeddingGateway;
    this.rerankGateway = rerankGateway;
    this.aiProperties = aiProperties;
    this.workSpaceQueryService = workSpaceQueryService;
    this.documentQueryApi = documentQueryApi;
  }

  public DefaultHybridSearchQueryService(
      ElasticsearchOperations elasticsearchOperations,
      EmbeddingGateway embeddingGateway,
      RerankGateway rerankGateway,
      AiProperties aiProperties,
      @Nullable WorkSpaceQueryService workSpaceQueryService) {
    this(
        elasticsearchOperations,
        embeddingGateway,
        rerankGateway,
        aiProperties,
        workSpaceQueryService,
        null);
  }

  public DefaultHybridSearchQueryService(
      ElasticsearchOperations elasticsearchOperations,
      EmbeddingGateway embeddingGateway,
      RerankGateway rerankGateway,
      AiProperties aiProperties) {
    this(elasticsearchOperations, embeddingGateway, rerankGateway, aiProperties, null, null);
  }

  @Override
  public List<ScoredDocChunkVO> hybridSearch(HybridSearchCommand command) {
    if (command == null) {
      return List.of();
    }

    long startTime = System.currentTimeMillis();

    // 1. 获取问题向量 (优先使用入参自带的向量，无则调用 EmbeddingGateway 计算)
    float[] vector = command.queryVector();
    if (vector == null || vector.length == 0) {
      try {
        vector = embeddingGateway.embed(command.query());
      } catch (Exception e) {
        log.warn(
            "【HybridSearch】调用向量网关提取问题向量失败，将回退纯词法召回: query=[{}], error={}",
            command.query(),
            e.getMessage());
      }
    }

    RerankStrategyEnum strategy = rerankGateway != null ? rerankGateway.getStrategy() : null;
    double minScoreThreshold = resolveMinScoreThreshold(command);
    int maxChunksPerDocument = resolveMaxChunksPerDocument();

    // 2. 检查多工作空间拓扑逻辑：若未指定单文档且存在多源配置与权重，走多空间加权召回
    List<SourceMeta> sources =
        (command.documentId() == null) ? resolveAvailableSources(command.workspaceId()) : List.of();
    boolean hasMultiSource =
        sources.size() > 1
            && command.weights() != null
            && !command.weights().isEmpty()
            && command.weights().entrySet().stream()
                .anyMatch(
                    e ->
                        sources.stream().anyMatch(s -> s.workspaceId().equals(e.getKey()))
                            && e.getValue() != null
                            && e.getValue() > 0);

    int targetTopK = resolveTargetTopK(command, hasMultiSource);

    if (sources.size() > 1 && !hasMultiSource) {
      log.info(
          "【HybridSearch】检测到当前空间存在 {} 个关联空间，但调用方未指定关联空间权重，按单空间逻辑仅检索当前主空间: workspaceId={}",
          sources.size() - 1,
          command.workspaceId());
    }

    List<ScoredDocChunkVO> finalResults;
    if (hasMultiSource) {
      finalResults =
          executeMultiWorkspaceHybridSearch(
              command,
              vector,
              sources,
              targetTopK,
              strategy,
              minScoreThreshold,
              maxChunksPerDocument);
    } else {
      finalResults =
          executeSingleWorkspaceHybridSearch(
              command, vector, targetTopK, strategy, minScoreThreshold, maxChunksPerDocument);
    }

    long cost = System.currentTimeMillis() - startTime;
    log.info(
        "【HybridSearch】检索与重排完成: query=[{}], 多源={}, 精排输出数={}, 阈值={}, 单文档切片上限={}, 耗时={}ms, 策略={}",
        command.query(),
        hasMultiSource,
        finalResults.size(),
        minScoreThreshold,
        maxChunksPerDocument,
        cost,
        strategy);

    return List.copyOf(finalResults);
  }

  /** 单工作空间混合检索执行流程 (保持 100% 向后兼容) */
  private List<ScoredDocChunkVO> executeSingleWorkspaceHybridSearch(
      HybridSearchCommand command,
      float[] vector,
      int targetTopK,
      RerankStrategyEnum strategy,
      double minScoreThreshold,
      int maxChunksPerDocument) {

    int candidateLimit = Math.max(targetTopK * 5, 50);

    // 1. 词法全文召回 (BM25 + IK 分词)
    List<DocChunkDocument> bm25Candidates = searchLexical(command, candidateLimit);

    // 2. 稠密向量召回 (ES 8.x kNN Dense Vector)
    List<DocChunkDocument> knnCandidates =
        (vector != null && vector.length > 0)
            ? searchVector(command, vector, candidateLimit)
            : List.of();

    log.info(
        "【HybridSearch】单空间双路初筛完成: query=[{}], workspaceId={}, BM25候选数={}, kNN候选数={}",
        command.query(),
        command.workspaceId(),
        bm25Candidates.size(),
        knnCandidates.size());

    if (bm25Candidates.isEmpty() && knnCandidates.isEmpty()) {
      return List.of();
    }

    if (strategy == RerankStrategyEnum.RRF) {
      List<DocChunkDocument> candidatesForRerank =
          new ArrayList<>(bm25Candidates.size() + knnCandidates.size());
      candidatesForRerank.addAll(bm25Candidates);
      candidatesForRerank.addAll(knnCandidates);

      RerankCommand rerankCommand =
          RerankCommand.of(
              command.query(),
              candidatesForRerank,
              Math.max(targetTopK, candidatesForRerank.size()));
      List<ScoredDocChunkVO> rerankedResults =
          rerankGateway != null ? rerankGateway.rerank(rerankCommand) : List.of();

      return selectFinalResults(
          rerankedResults,
          command.query(),
          vector,
          strategy,
          minScoreThreshold,
          maxChunksPerDocument,
          targetTopK);
    } else if (knnCandidates.isEmpty()) {
      RerankCommand rerankCommand =
          RerankCommand.of(
              command.query(), bm25Candidates, Math.max(targetTopK, bm25Candidates.size()));
      List<ScoredDocChunkVO> rerankedResults =
          rerankGateway != null ? rerankGateway.rerank(rerankCommand) : List.of();
      return selectFinalResults(
              rerankedResults,
              command.query(),
              vector,
              strategy,
              minScoreThreshold,
              maxChunksPerDocument,
              targetTopK)
          .stream()
          .map(vo -> ScoredDocChunkVO.of(vo.chunk(), vo.score(), vo.originalRank(), "BM25"))
          .toList();
    } else {
      return executeQuotaHybridSearch(
          command,
          vector,
          bm25Candidates,
          knnCandidates,
          strategy,
          minScoreThreshold,
          maxChunksPerDocument,
          targetTopK);
    }
  }

  /** 多工作空间加权召回、余量自适应补偿与统一 Rerank 精排 */
  private List<ScoredDocChunkVO> executeMultiWorkspaceHybridSearch(
      HybridSearchCommand command,
      float[] vector,
      List<SourceMeta> sources,
      int targetTopK,
      RerankStrategyEnum strategy,
      double minScoreThreshold,
      int maxChunksPerDocument) {

    Long primaryId = command.workspaceId();
    Map<Long, Integer> quotas = calculateQuotas(targetTopK, command.weights(), sources, primaryId);

    Set<Long> allSpaceIds =
        sources.stream().map(SourceMeta::workspaceId).collect(Collectors.toSet());
    allSpaceIds.add(primaryId);
    Map<Long, String> fetchedNames = Map.of();
    if (workSpaceQueryService != null) {
      try {
        Map<Long, String> result = workSpaceQueryService.getWorkspaceNames(allSpaceIds);
        if (result != null) {
          fetchedNames = result;
        }
      } catch (Exception e) {
        log.debug("【HybridSearch】获取工作空间名称映射异常忽略: {}", e.getMessage());
      }
    }
    final Map<Long, String> spaceNames = fetchedNames;
    String primaryName = spaceNames.getOrDefault(primaryId, "空间#" + primaryId);

    List<String> associatedNames =
        sources.stream()
            .filter(s -> !s.workspaceId().equals(primaryId))
            .map(
                s ->
                    spaceNames.getOrDefault(s.workspaceId(), "空间#" + s.workspaceId())
                        + "(id="
                        + s.workspaceId()
                        + ")")
            .toList();

    log.info(
        "【HybridSearch】启动跨空间多源联合检索: 主空间=[{}](id={}), 涉及关联空间={}, 目标TopK={}",
        primaryName,
        primaryId,
        associatedNames,
        targetTopK);

    // 1. 各关联空间分别召回 (按需过滤 PUBLIC 或细粒度授权文档)，统计不足配额差额
    Map<Long, List<DocChunkDocument>> selectedChunksBySpace = new HashMap<>();
    int totalDeficit = 0;

    for (SourceMeta source : sources) {
      if (source.workspaceId().equals(primaryId)) {
        continue;
      }
      String sourceName =
          spaceNames.getOrDefault(source.workspaceId(), "空间#" + source.workspaceId());
      String sourceDesc = "EXPLICIT".equals(source.sourceType()) ? "横向关联空间" : "直系子空间";

      int quota = quotas.getOrDefault(source.workspaceId(), 0);
      if (quota <= 0) {
        selectedChunksBySpace.put(source.workspaceId(), List.of());
        continue;
      }

      // 若为显式横向关联且未授权任何文档，跳过召回并将配额补偿回主空间
      if ("EXPLICIT".equals(source.sourceType())
          && (source.allowedDocIds() == null || source.allowedDocIds().isEmpty())) {
        log.info(
            "【HybridSearch】检索从关联空间 [{}] 中跳过: 未授权任何可用文档，分配配额 {} 全额补偿回主空间 (workspaceId={})",
            sourceName,
            quota,
            source.workspaceId());
        selectedChunksBySpace.put(source.workspaceId(), List.of());
        totalDeficit += quota;
        continue;
      }

      log.info(
          "【HybridSearch】检索从关联空间 [{}] 中同步检索: workspaceId={}, 关联类型={}, 分配配额={}",
          sourceName,
          source.workspaceId(),
          sourceDesc,
          quota);

      int candidateLimit = Math.max(quota * 4, 20);
      List<DocChunkDocument> bm25 =
          searchLexicalForWorkspace(
              command,
              source.workspaceId(),
              source.onlyPublic(),
              source.allowedDocIds(),
              candidateLimit);
      List<DocChunkDocument> knn =
          (vector != null && vector.length > 0)
              ? searchVectorForWorkspace(
                  command,
                  vector,
                  source.workspaceId(),
                  source.onlyPublic(),
                  source.allowedDocIds(),
                  candidateLimit)
              : List.of();

      List<DocChunkDocument> merged = interleaveAndDeduplicate(knn, bm25);
      int actual = Math.min(quota, merged.size());
      selectedChunksBySpace.put(source.workspaceId(), merged.subList(0, actual));

      log.info(
          "【HybridSearch】从关联空间 [{}] 检索完成: 召回候选数(BM25={}, kNN={}), 实际采用切片数={}/{}",
          sourceName,
          bm25.size(),
          knn.size(),
          actual,
          quota);

      if (actual < quota) {
        totalDeficit += (quota - actual);
      }
    }

    // 2. 主空间召回：基础配额 + 关联空间缺额自适应补偿
    int basePrimaryQuota = quotas.getOrDefault(primaryId, targetTopK);
    int primaryQuota = basePrimaryQuota + totalDeficit;
    log.info(
        "【HybridSearch】检索从主空间 [{}] 中同步检索: workspaceId={}, 基础配额={}, 获得缺额补偿={}, 实际配额={}",
        primaryName,
        primaryId,
        basePrimaryQuota,
        totalDeficit,
        primaryQuota);

    int primaryCandidateLimit = Math.max(primaryQuota * 4, Math.max(targetTopK * 4, 50));
    List<DocChunkDocument> primaryBm25 =
        searchLexicalForWorkspace(command, primaryId, false, null, primaryCandidateLimit);
    List<DocChunkDocument> primaryKnn =
        (vector != null && vector.length > 0)
            ? searchVectorForWorkspace(
                command, vector, primaryId, false, null, primaryCandidateLimit)
            : List.of();

    List<DocChunkDocument> primaryMerged = interleaveAndDeduplicate(primaryKnn, primaryBm25);
    int primaryActual = Math.min(primaryQuota, primaryMerged.size());
    selectedChunksBySpace.put(primaryId, primaryMerged.subList(0, primaryActual));

    log.info(
        "【HybridSearch】从主空间 [{}] 检索完成: 召回候选数(BM25={}, kNN={}), 实际采用切片数={}/{}",
        primaryName,
        primaryBm25.size(),
        primaryKnn.size(),
        primaryActual,
        primaryQuota);

    // 3. 汇聚所有空间召回的候选切片并去重
    List<DocChunkDocument> allCandidates = new ArrayList<>();
    Set<String> seenIds = new HashSet<>();
    for (List<DocChunkDocument> list : selectedChunksBySpace.values()) {
      for (DocChunkDocument doc : list) {
        if (doc != null && doc.getId() != null && seenIds.add(doc.getId())) {
          allCandidates.add(doc);
        }
      }
    }

    log.info(
        "【HybridSearch】跨空间多源联合检索初筛汇总: 共有 {} 个空间参与并命中候选切片 (总候选切片数={})",
        selectedChunksBySpace.size(),
        allCandidates.size());

    if (allCandidates.isEmpty()) {
      return List.of();
    }

    // 4. 统一 Rerank 精排：多源候选池统一送入模型重排，消除跨空间得分尺度差异
    if (strategy != null && rerankGateway != null && strategy != RerankStrategyEnum.NONE) {
      RerankCommand rerankCommand =
          RerankCommand.of(
              command.query(), allCandidates, Math.max(targetTopK, allCandidates.size()));
      List<ScoredDocChunkVO> rerankedResults = rerankGateway.rerank(rerankCommand);
      return selectFinalResults(
          rerankedResults,
          command.query(),
          vector,
          strategy,
          minScoreThreshold,
          maxChunksPerDocument,
          targetTopK);
    }

    // 降级：无 Rerank 时按余弦相似度与平滑排位选择
    List<ScoredDocChunkVO> fallbackList = new ArrayList<>(allCandidates.size());
    for (int i = 0; i < allCandidates.size(); i++) {
      DocChunkDocument doc = allCandidates.get(i);
      Double cos = cosineSimilarity(vector, doc.getContentVector());
      double score = cos != null ? cos : (1.0 / (60.0 + (i + 1)));
      fallbackList.add(ScoredDocChunkVO.of(doc, score, i + 1, "HYBRID"));
    }
    return selectFinalResults(
        fallbackList,
        command.query(),
        vector,
        strategy,
        minScoreThreshold,
        maxChunksPerDocument,
        targetTopK);
  }

  /** 解析最低相似度阈值：优先取调用方入参，其次取全局 RAG 配置 */
  private double resolveMinScoreThreshold(HybridSearchCommand command) {
    if (command.minScore() != null) {
      return command.minScore();
    }
    return (aiProperties != null && aiProperties.getRag() != null)
        ? aiProperties.getRag().getSimilarityThreshold()
        : 0.0;
  }

  /** 解析单文档切片数量上限：{@code <= 0} 表示关闭文档级多样性保护 */
  private int resolveMaxChunksPerDocument() {
    return (aiProperties != null && aiProperties.getRag() != null)
        ? aiProperties.getRag().getMaxChunksPerDocument()
        : 0;
  }

  /**
   * 终态结果选择：真实余弦回填 + 相似度阈值过滤 + 文档级多样性上限 + Top-K 截取
   *
   * <p>核心动机：同一文档内关键词高频重复时，双路召回会被同文档的近似切片刷屏，导致其他文档甚至真正需要的切片被挤出结果。 此处以真实向量余弦相似度重新打分，并按 {@code
   * maxChunksPerDocument} 限制单文档占比。
   */
  private List<ScoredDocChunkVO> selectFinalResults(
      List<ScoredDocChunkVO> rerankedResults,
      String query,
      float[] queryVector,
      RerankStrategyEnum strategy,
      double minScoreThreshold,
      int maxChunksPerDocument,
      int targetTopK) {

    Set<String> seenChunkIds = new HashSet<>();
    List<CandidateItem> candidateItems = new ArrayList<>(rerankedResults.size());
    boolean isTableIntent = isTableQuery(query);

    for (ScoredDocChunkVO item : rerankedResults) {
      if (item == null || item.chunk() == null || item.chunk().getId() == null) {
        continue;
      }
      if (!seenChunkIds.add(item.chunk().getId())) {
        continue;
      }

      DocChunkDocument chunk = item.chunk();
      Double cosine = cosineSimilarity(queryVector, chunk.getContentVector());
      double baseScore;
      if (strategy == RerankStrategyEnum.CROSS_ENCODER && item.score() > 0.0) {
        baseScore = item.score();
      } else {
        baseScore = (cosine != null) ? cosine : item.score();
      }

      // 阈值过滤机制：基于真实语义相似度进行严格过滤与结构保护 (表结构宽松阈值：table 切片或包含数据表结构定义时，宽松 0.65 保护)
      boolean isTable = isTableIntent && isTableChunk(chunk);
      double effectiveThreshold = isTable ? minScoreThreshold * 0.65 : minScoreThreshold;
      if (strategy != RerankStrategyEnum.RRF
          && (strategy == RerankStrategyEnum.CROSS_ENCODER || cosine != null)) {
        if (baseScore < effectiveThreshold) {
          continue;
        }
      }

      // 综合排序分数计算：
      // 1. 基础分取真实余弦相似度 (或重排打分)；
      // 2. 结合初筛原始排位平滑增益 (RRF 倒数排位)，尊重双路检索初筛结果；
      // 3. 意图感知加权：当用户查询显式提及"表"或"table"且切片类型为结构化表格时，赋予类型对齐增益。
      double rankBonus = 1.0 / (60.0 + item.originalRank());
      double intentBonus = isTable ? 0.05 : 0.0;
      double sortScore = baseScore + rankBonus + intentBonus;

      candidateItems.add(
          new CandidateItem(
              chunk,
              baseScore,
              sortScore,
              item.originalRank(),
              StrUtil.blankToDefault(item.recallSource(), "RERANK")));
    }

    // 按综合排序得分降序排列（分数相同时保持原始顺序）
    candidateItems.sort(Comparator.comparingDouble(CandidateItem::sortScore).reversed());

    List<ScoredDocChunkVO> selected = new ArrayList<>(Math.min(targetTopK, candidateItems.size()));
    List<CandidateItem> backfillPool = new ArrayList<>();
    Map<String, Integer> perDocumentCount = new HashMap<>();
    int diversityTruncated = 0;

    // 第一阶段：多样性优先（按 maxChunksPerDocument 限制单文档初选量，保证多文档均衡展示）
    for (CandidateItem item : candidateItems) {
      if (selected.size() >= targetTopK) {
        break;
      }
      String documentId = item.chunk().getDocumentId();
      if (maxChunksPerDocument <= 0 || StrUtil.isBlank(documentId)) {
        selected.add(
            ScoredDocChunkVO.of(
                item.chunk(), item.displayScore(), item.originalRank(), item.recallSource()));
        continue;
      }
      int used = perDocumentCount.getOrDefault(documentId, 0);
      if (used < maxChunksPerDocument) {
        perDocumentCount.put(documentId, used + 1);
        selected.add(
            ScoredDocChunkVO.of(
                item.chunk(), item.displayScore(), item.originalRank(), item.recallSource()));
      } else {
        backfillPool.add(item);
        diversityTruncated++;
      }
    }

    // 第二阶段：目标 Top-K 兜底补齐（Backfill）
    // 当多文档未填满 targetTopK（如集中在单文档或仅有少数文档匹配）时，从回填池中按相似度降序依次补齐，
    // 杜绝因多样性硬截断导致召回数量骤降、关键设计全貌丢失的问题。
    if (selected.size() < targetTopK && !backfillPool.isEmpty()) {
      int beforeCount = selected.size();
      for (CandidateItem item : backfillPool) {
        if (selected.size() >= targetTopK) {
          break;
        }
        selected.add(
            ScoredDocChunkVO.of(
                item.chunk(), item.displayScore(), item.originalRank(), item.recallSource()));
      }
      log.info(
          "【HybridSearch】多样性回填生效: 首轮仅选出 {} 条，从备选池按分数补齐 {} 条同文档切片，最终输出 {} 条",
          beforeCount,
          selected.size() - beforeCount,
          selected.size());
    } else if (diversityTruncated > 0) {
      log.info("【HybridSearch】文档级多样性上限生效: 单文档最多 {} 条，多余候选已排除", maxChunksPerDocument);
    }

    return List.copyOf(selected);
  }

  private boolean isTableQuery(String query) {
    if (StrUtil.isBlank(query)) {
      return false;
    }
    String lower = query.toLowerCase();
    return lower.contains("表") || lower.contains("table");
  }

  private boolean isTableChunk(DocChunkDocument chunk) {
    if (chunk == null) {
      return false;
    }
    if ("table".equalsIgnoreCase(chunk.getChunkType())) {
      return true;
    }
    String content = chunk.getContent();
    if (StrUtil.isNotBlank(content)) {
      return content.contains("【数据表结构定义】")
          || (content.contains("|")
              && (content.contains("字段") || content.contains("类型") || content.contains("注释")));
    }
    return false;
  }

  /**
   * 对词法全文检索关键词进行自然语言提问降噪与核心实体提取
   *
   * <p>Dense 向量检索需保留完整自然语言语义，而 BM25 作为词袋 TF-IDF 算法，极易被自然语言中的提问疑问词、
   * 指令助词（如“有哪些”、“要返回”、“请问”）严重稀释和污染，导致命中大量包含“返回值”、“有哪些”的高频无关接口文档。 此处剥离自然语言提问助词与指令词，提取纯净的实体与技术核心词交付
   * BM25 检索。
   */
  private String cleanLexicalQuery(String rawQuery) {
    if (StrUtil.isBlank(rawQuery)) {
      return "";
    }
    // 1. 过滤自然语言提问与格式指令虚词（如“有哪些”、“要返回”、“请问”、“列出”）
    String cleaned =
        rawQuery.replaceAll("(?i)(有哪些|有什么|是什么|怎么做|如何|为什么|请问|请告诉我|告诉我|请给出|请列出|要返回|需要返回|详细说明)", " ");
    // 2. 过滤常见无检索区分度的连词、助词
    cleaned =
        cleaned.replaceAll(
            "(?i)(?:^|\\s+|(?<=[\u4e00-\u9fa5a-zA-Z0-9]))(和|及|与|或|的|了|吗|呢|吧|啊|呀)(?:$|\\s+|(?=[\u4e00-\u9fa5a-zA-Z0-9]))",
            " ");
    // 3. 规范化空白字符
    cleaned = cleaned.replaceAll("\\s+", " ").trim();
    return StrUtil.isNotBlank(cleaned) ? cleaned : rawQuery.trim();
  }

  private record CandidateItem(
      DocChunkDocument chunk,
      double displayScore,
      double sortScore,
      int originalRank,
      String recallSource) {}

  /**
   * 计算查询向量与切片向量的真实余弦相似度
   *
   * @return 相似度取值 [-1, 1]；任一侧向量缺失、为零向量或维度不一致时返回 {@code null}（表示无法给出真实语义分数）
   */
  private Double cosineSimilarity(float[] queryVector, float[] chunkVector) {
    if (queryVector == null
        || queryVector.length == 0
        || chunkVector == null
        || chunkVector.length == 0) {
      return null;
    }
    if (queryVector.length != chunkVector.length) {
      log.warn(
          "【HybridSearch】查询向量与切片向量维度不一致，跳过真实相似度计算: queryDim={}, chunkDim={}",
          queryVector.length,
          chunkVector.length);
      return null;
    }

    double dot = 0.0;
    double queryNorm = 0.0;
    double chunkNorm = 0.0;
    for (int i = 0; i < queryVector.length; i++) {
      dot += (double) queryVector[i] * chunkVector[i];
      queryNorm += (double) queryVector[i] * queryVector[i];
      chunkNorm += (double) chunkVector[i] * chunkVector[i];
    }
    if (queryNorm <= 0.0 || chunkNorm <= 0.0) {
      return null;
    }
    return dot / (Math.sqrt(queryNorm) * Math.sqrt(chunkNorm));
  }

  /** 执行 BM25 词法全文检索 (同空间全量/ACL权限) */
  private List<DocChunkDocument> searchLexical(HybridSearchCommand command, int limit) {
    return searchLexicalForWorkspace(command, command.workspaceId(), false, limit);
  }

  /** 执行指定工作空间的 BM25 词法全文检索 (兼容 4 参数重载) */
  private List<DocChunkDocument> searchLexicalForWorkspace(
      HybridSearchCommand command, Long workspaceId, boolean onlyPublic, int limit) {
    return searchLexicalForWorkspace(command, workspaceId, onlyPublic, null, limit);
  }

  /** 执行指定工作空间的 BM25 词法全文检索 (支持细粒度授权文档与 PUBLIC 过滤) */
  private List<DocChunkDocument> searchLexicalForWorkspace(
      HybridSearchCommand command,
      Long workspaceId,
      boolean onlyPublic,
      List<Long> allowedDocIds,
      int limit) {
    try {
      String lexicalQuery = cleanLexicalQuery(command.query());
      NativeQueryBuilder builder = NativeQuery.builder();
      builder.withQuery(
          q ->
              q.bool(
                  b -> {
                    b.must(
                        m ->
                            m.multiMatch(
                                mm ->
                                    mm.query(lexicalQuery)
                                        .type(TextQueryType.BestFields)
                                        .tieBreaker(0.5)
                                        .fields(
                                            List.of(
                                                "content^3",
                                                "contextPrefix^2.5",
                                                "title^1",
                                                "headerPath^1.5"))));

                    // Filter 1: 工作区隔离过滤器
                    b.filter(
                        f ->
                            f.term(t -> t.field("workspaceId").value(String.valueOf(workspaceId))));

                    // Filter 2: 可选文档范围过滤
                    if (command.documentId() != null) {
                      b.filter(
                          f ->
                              f.term(
                                  t ->
                                      t.field("documentId")
                                          .value(String.valueOf(command.documentId()))));
                    }

                    // Filter 3: 可选分类范围过滤
                    if (command.categoryId() != null) {
                      b.filter(
                          f ->
                              f.term(
                                  t ->
                                      t.field("categoryId")
                                          .value(String.valueOf(command.categoryId()))));
                    }

                    // Filter 4: 可选标签精确过滤
                    if (command.tags() != null && !command.tags().isEmpty()) {
                      List<FieldValue> tagValues =
                          command.tags().stream().map(FieldValue::of).toList();
                      b.filter(f -> f.terms(ts -> ts.field("tags").terms(t -> t.value(tagValues))));
                    }

                    // Filter 5: 文档可见性与租户细粒度 ACL 过滤 (优先应用细粒度授权文档名单)
                    if (allowedDocIds != null && !allowedDocIds.isEmpty()) {
                      List<FieldValue> docIdValues =
                          allowedDocIds.stream().map(String::valueOf).map(FieldValue::of).toList();
                      b.filter(
                          f ->
                              f.terms(
                                  ts -> ts.field("documentId").terms(t -> t.value(docIdValues))));
                    } else if (onlyPublic) {
                      b.filter(f -> f.term(t -> t.field("visibility").value("PUBLIC")));
                    } else {
                      applyAclFilter(b, command);
                    }

                    return b;
                  }));

      builder.withPageable(PageRequest.of(0, limit));
      SearchHits<DocChunkDocument> hits =
          elasticsearchOperations.search(builder.build(), DocChunkDocument.class);

      if (hits == null || hits.isEmpty()) {
        return List.of();
      }
      return hits.getSearchHits().stream()
          .map(org.springframework.data.elasticsearch.core.SearchHit::getContent)
          .toList();
    } catch (Exception e) {
      log.warn(
          "【HybridSearch】BM25 词法全文检索异常: workspaceId={}, query=[{}], error={}",
          workspaceId,
          command.query(),
          e.getMessage());
      return List.of();
    }
  }

  /** 执行 kNN 向量相似度检索 (同空间全量/ACL权限) */
  private List<DocChunkDocument> searchVector(
      HybridSearchCommand command, float[] queryVector, int limit) {
    return searchVectorForWorkspace(
        command, queryVector, command.workspaceId(), false, null, limit);
  }

  /** 执行指定工作空间的 kNN 向量相似度检索 (兼容 5 参数重载) */
  private List<DocChunkDocument> searchVectorForWorkspace(
      HybridSearchCommand command,
      float[] queryVector,
      Long workspaceId,
      boolean onlyPublic,
      int limit) {
    return searchVectorForWorkspace(command, queryVector, workspaceId, onlyPublic, null, limit);
  }

  /** 执行指定工作空间的 kNN 向量相似度检索 (支持细粒度授权文档与 PUBLIC 过滤) */
  private List<DocChunkDocument> searchVectorForWorkspace(
      HybridSearchCommand command,
      float[] queryVector,
      Long workspaceId,
      boolean onlyPublic,
      List<Long> allowedDocIds,
      int limit) {
    try {
      List<Float> vectorList = new ArrayList<>(queryVector.length);
      for (float f : queryVector) {
        vectorList.add(f);
      }

      NativeQueryBuilder builder = NativeQuery.builder();
      builder.withKnnSearches(
          knn -> {
            knn.field("content_vector")
                .queryVector(vectorList)
                .k(limit)
                .numCandidates(Math.max(limit * 2, 50))
                .filter(
                    f ->
                        f.bool(
                            b -> {
                              // Filter 1: 工作区隔离过滤器
                              b.filter(
                                  bf ->
                                      bf.term(
                                          t ->
                                              t.field("workspaceId")
                                                  .value(String.valueOf(workspaceId))));

                              // Filter 2: 可选文档范围过滤
                              if (command.documentId() != null) {
                                b.filter(
                                    bf ->
                                        bf.term(
                                            t ->
                                                t.field("documentId")
                                                    .value(String.valueOf(command.documentId()))));
                              }

                              // Filter 3: 可选分类范围过滤
                              if (command.categoryId() != null) {
                                b.filter(
                                    bf ->
                                        bf.term(
                                            t ->
                                                t.field("categoryId")
                                                    .value(String.valueOf(command.categoryId()))));
                              }

                              // Filter 4: 可选标签精确过滤
                              if (command.tags() != null && !command.tags().isEmpty()) {
                                List<FieldValue> tagValues =
                                    command.tags().stream().map(FieldValue::of).toList();
                                b.filter(
                                    bf ->
                                        bf.terms(
                                            ts -> ts.field("tags").terms(t -> t.value(tagValues))));
                              }

                              // Filter 5: 文档可见性与租户细粒度 ACL 过滤 (优先应用细粒度授权文档名单)
                              if (allowedDocIds != null && !allowedDocIds.isEmpty()) {
                                List<FieldValue> docIdValues =
                                    allowedDocIds.stream()
                                        .map(String::valueOf)
                                        .map(FieldValue::of)
                                        .toList();
                                b.filter(
                                    bf ->
                                        bf.terms(
                                            ts ->
                                                ts.field("documentId")
                                                    .terms(t -> t.value(docIdValues))));
                              } else if (onlyPublic) {
                                b.filter(bf -> bf.term(t -> t.field("visibility").value("PUBLIC")));
                              } else {
                                applyAclFilter(b, command);
                              }

                              return b;
                            }));
            return knn;
          });

      builder.withPageable(PageRequest.of(0, limit));
      SearchHits<DocChunkDocument> hits =
          elasticsearchOperations.search(builder.build(), DocChunkDocument.class);

      if (hits == null || hits.isEmpty()) {
        return List.of();
      }
      return hits.getSearchHits().stream()
          .map(org.springframework.data.elasticsearch.core.SearchHit::getContent)
          .toList();
    } catch (Exception e) {
      log.warn(
          "【HybridSearch】kNN 向量检索异常: workspaceId={}, query=[{}], error={}",
          workspaceId,
          command.query(),
          e.getMessage());
      return List.of();
    }
  }

  /** 对双路初筛结果进行交替轮询与去重合并 */
  private List<DocChunkDocument> interleaveAndDeduplicate(
      List<DocChunkDocument> list1, List<DocChunkDocument> list2) {
    List<DocChunkDocument> result = new ArrayList<>();
    Set<String> seenIds = new HashSet<>();
    int maxLen = Math.max(list1.size(), list2.size());

    for (int i = 0; i < maxLen; i++) {
      if (i < list1.size()) {
        DocChunkDocument doc1 = list1.get(i);
        if (doc1 != null && doc1.getId() != null && seenIds.add(doc1.getId())) {
          result.add(doc1);
        }
      }
      if (i < list2.size()) {
        DocChunkDocument doc2 = list2.get(i);
        if (doc2 != null && doc2.getId() != null && seenIds.add(doc2.getId())) {
          result.add(doc2);
        }
      }
    }
    return result;
  }

  /**
   * 配额隔离混合检索执行：向量精排 Top-N + BM25 词法保底 Top-M
   *
   * @param command 原始检索命令
   * @param vector 查询向量
   * @param bm25Candidates 词法检索候选集
   * @param knnCandidates 稠密向量检索候选集
   * @param strategy 重排算法策略
   * @param minScoreThreshold 最低相似度阈值
   * @param maxChunksPerDocument 单文档切片上限
   * @param targetTopK 最终目标召回总数
   * @return 最终融合切片列表
   */
  private List<ScoredDocChunkVO> executeQuotaHybridSearch(
      HybridSearchCommand command,
      float[] vector,
      List<DocChunkDocument> bm25Candidates,
      List<DocChunkDocument> knnCandidates,
      RerankStrategyEnum strategy,
      double minScoreThreshold,
      int maxChunksPerDocument,
      int targetTopK) {

    int vectorQuota = resolveVectorTopK(targetTopK);
    int lexicalQuota = resolveLexicalTopK(targetTopK, vectorQuota);

    // 1. 向量路交由 Rerank Gateway 精排处理并截取 vectorQuota 条
    List<ScoredDocChunkVO> vectorSelected = new ArrayList<>();
    if (!knnCandidates.isEmpty()) {
      RerankCommand rerankCommand =
          RerankCommand.of(
              command.query(), knnCandidates, Math.max(vectorQuota, knnCandidates.size()));
      List<ScoredDocChunkVO> rerankedKnn =
          rerankGateway != null ? rerankGateway.rerank(rerankCommand) : List.of();

      vectorSelected =
          new ArrayList<>(
              selectFinalResults(
                  rerankedKnn,
                  command.query(),
                  vector,
                  strategy,
                  minScoreThreshold,
                  maxChunksPerDocument,
                  vectorQuota));
    }

    // 2. 收集已选入向量路的切片 ID 与文档计数，用于 BM25 词法保底排重与多样性协同
    Set<String> selectedChunkIds = new HashSet<>();
    Map<String, Integer> perDocumentCount = new HashMap<>();
    for (ScoredDocChunkVO vo : vectorSelected) {
      if (vo != null && vo.chunk() != null && vo.chunk().getId() != null) {
        selectedChunkIds.add(vo.chunk().getId());
        String docId = vo.chunk().getDocumentId();
        if (StrUtil.isNotBlank(docId)) {
          perDocumentCount.put(docId, perDocumentCount.getOrDefault(docId, 0) + 1);
        }
      }
    }

    // 3. 词法路 (BM25) 独立保底配额提取 (排除已入围切片，确保精确关键词命中切片保底入围)
    int remainingQuota = Math.max(0, targetTopK - vectorSelected.size());
    int targetLexicalCount = Math.min(lexicalQuota, remainingQuota);
    // 若向量路入围不足（如向量无匹配或因阈值过滤淘汰），允许词法路弹性补齐差额
    if (vectorSelected.size() < vectorQuota) {
      targetLexicalCount = remainingQuota;
    }

    List<ScoredDocChunkVO> lexicalSelected = new ArrayList<>();
    List<ScoredDocChunkVO> lexicalBackfill = new ArrayList<>();
    int lexicalRankCounter = vectorSelected.size() + 1;

    for (int i = 0; i < bm25Candidates.size(); i++) {
      if (lexicalSelected.size() >= targetLexicalCount) {
        break;
      }
      DocChunkDocument doc = bm25Candidates.get(i);
      if (doc == null || doc.getId() == null || selectedChunkIds.contains(doc.getId())) {
        continue;
      }

      Double cosine = cosineSimilarity(vector, doc.getContentVector());
      // 词法保底打分：优先取真实向量余弦；无向量时取词法排位平滑衰减
      double score = (cosine != null && cosine > 0.0) ? cosine : (1.0 / (60.0 + (i + 1)));

      String documentId = doc.getDocumentId();
      ScoredDocChunkVO vo = ScoredDocChunkVO.of(doc, score, lexicalRankCounter++, "BM25");
      if (maxChunksPerDocument <= 0 || StrUtil.isBlank(documentId)) {
        lexicalSelected.add(vo);
        selectedChunkIds.add(doc.getId());
      } else {
        int currentCount = perDocumentCount.getOrDefault(documentId, 0);
        if (currentCount < maxChunksPerDocument) {
          perDocumentCount.put(documentId, currentCount + 1);
          lexicalSelected.add(vo);
          selectedChunkIds.add(doc.getId());
        } else {
          lexicalBackfill.add(vo);
        }
      }
    }

    // 若多样性初选后仍未达到目标数量，从词法备选池补齐
    if (lexicalSelected.size() < targetLexicalCount && !lexicalBackfill.isEmpty()) {
      for (ScoredDocChunkVO backfillItem : lexicalBackfill) {
        if (lexicalSelected.size() >= targetLexicalCount) {
          break;
        }
        if (selectedChunkIds.add(backfillItem.chunk().getId())) {
          lexicalSelected.add(backfillItem);
        }
      }
    }

    // 4. 合并最终结果：向量精排 Top-N + 词法保底 Top-M
    List<ScoredDocChunkVO> finalResults =
        new ArrayList<>(vectorSelected.size() + lexicalSelected.size());
    finalResults.addAll(vectorSelected);
    finalResults.addAll(lexicalSelected);

    log.info(
        "【HybridSearch】配额隔离混合召回完成: 目标TopK={}, 向量精排入围={}, 词法保底入围={}, 总输出={}",
        targetTopK,
        vectorSelected.size(),
        lexicalSelected.size(),
        finalResults.size());

    return finalResults;
  }

  /**
   * 文档可见性与租户细粒度 ACL 过滤 (非管理员用户限制访问私密文档)
   *
   * <p>规则：
   *
   * <ul>
   *   <li>若当前用户为空间管理员 (command.isAdmin() == true)，跳过 ACL 过滤，允许查阅空间内全部切片；
   *   <li>若为普通成员/访客，限制切片满足下列任一条件 (should + minimumShouldMatch=1)：
   *       <ol>
   *         <li>visibility 为 PUBLIC 或 INTERNAL；
   *         <li>visibility 为 PRIVATE 且 createBy 等于当前登录用户 ID；
   *         <li>历史切片向后兼容：切片缺少 visibility 字段 (must_not exists)，视为空间成员默认可读。
   *       </ol>
   * </ul>
   */
  private void applyAclFilter(
      co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder b,
      HybridSearchCommand command) {
    if (command.isAdmin()) {
      return;
    }

    b.filter(
        af ->
            af.bool(
                ab -> {
                  // 1. PUBLIC 或 INTERNAL 可见
                  ab.should(
                      s ->
                          s.terms(
                              ts ->
                                  ts.field("visibility")
                                      .terms(
                                          t ->
                                              t.value(
                                                  List.of(
                                                      FieldValue.of("PUBLIC"),
                                                      FieldValue.of("INTERNAL"))))));

                  // 2. 自己创建的 PRIVATE 文档
                  if (command.userId() != null) {
                    ab.should(
                        s ->
                            s.bool(
                                pb ->
                                    pb.filter(
                                            pf ->
                                                pf.term(
                                                    t -> t.field("visibility").value("PRIVATE")))
                                        .filter(
                                            pf ->
                                                pf.term(
                                                    t ->
                                                        t.field("createBy")
                                                            .value(
                                                                String.valueOf(
                                                                    command.userId()))))));
                  }

                  // 3. 兼容历史数据：若切片未落库 visibility 字段，则默认允许工作区成员访问
                  ab.should(
                      s -> s.bool(hb -> hb.mustNot(mn -> mn.exists(e -> e.field("visibility")))));

                  ab.minimumShouldMatch("1");
                  return ab;
                }));
  }

  private int resolveVectorTopK(int totalTopK) {
    if (aiProperties != null && aiProperties.getRag() != null) {
      int cfgVector = aiProperties.getRag().getVectorTopK();
      int cfgTotal = aiProperties.getRag().getTopK();
      if (totalTopK == cfgTotal) {
        return cfgVector;
      }
      double ratio = cfgTotal > 0 ? (double) cfgVector / cfgTotal : 0.75;
      return Math.max(1, (int) Math.round(totalTopK * ratio));
    }
    return Math.max(1, (int) Math.round(totalTopK * 0.75));
  }

  private int resolveLexicalTopK(int totalTopK, int vectorQuota) {
    return Math.max(0, totalTopK - vectorQuota);
  }

  private int resolveTargetTopK(HybridSearchCommand command, boolean hasMultiSource) {
    int minTopK =
        (aiProperties != null && aiProperties.getRag() != null)
            ? aiProperties.getRag().getMinTopK()
            : 20;
    int maxTopK =
        (aiProperties != null && aiProperties.getRag() != null)
            ? aiProperties.getRag().getMaxTopK()
            : 50;

    int requested = command.topK();
    if (hasMultiSource) {
      if (requested <= 0 || requested < minTopK) {
        return minTopK;
      }
      return Math.min(requested, maxTopK);
    }

    if (requested > 0) {
      return Math.min(requested, maxTopK);
    }
    if (aiProperties != null && aiProperties.getRag() != null) {
      return aiProperties.getRag().getTopK();
    }
    return minTopK;
  }

  private List<SourceMeta> resolveAvailableSources(Long primaryId) {
    List<SourceMeta> sources = new ArrayList<>();
    sources.add(new SourceMeta(primaryId, "PRIMARY", false, null));

    if (workSpaceQueryService != null) {
      try {
        List<Long> childIds = workSpaceQueryService.listChildWorkspaceIds(primaryId);
        if (childIds != null) {
          for (Long childId : childIds) {
            if (childId != null && !childId.equals(primaryId)) {
              sources.add(new SourceMeta(childId, "CHILD_AUTO", true, null));
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
              sources.add(new SourceMeta(linkedId, "EXPLICIT", false, docIds));
            }
          }
        }
      } catch (Exception e) {
        log.warn(
            "【HybridSearch】查询横向关联空间及文档清单异常: primaryId={}, error={}", primaryId, e.getMessage());
      }
    }

    return sources;
  }

  private Map<Long, Integer> calculateQuotas(
      int targetTopK, Map<Long, Integer> weights, List<SourceMeta> sources, Long primaryId) {

    Map<Long, Integer> allocated = new HashMap<>();
    if (weights == null || weights.isEmpty()) {
      allocated.put(primaryId, targetTopK);
      return allocated;
    }

    Map<Long, Integer> validWeights = new HashMap<>();
    int sum = 0;
    for (SourceMeta meta : sources) {
      Integer w = weights.get(meta.workspaceId());
      if (w != null && w > 0) {
        validWeights.put(meta.workspaceId(), w);
        sum += w;
      } else {
        validWeights.put(meta.workspaceId(), 0);
      }
    }

    if (sum <= 0) {
      allocated.put(primaryId, targetTopK);
      return allocated;
    }

    int allocatedSum = 0;
    for (SourceMeta meta : sources) {
      int w = validWeights.getOrDefault(meta.workspaceId(), 0);
      int quota = (int) Math.floor((double) targetTopK * w / sum);
      allocated.put(meta.workspaceId(), quota);
      allocatedSum += quota;
    }

    int remainder = targetTopK - allocatedSum;
    if (remainder > 0) {
      allocated.put(primaryId, allocated.getOrDefault(primaryId, 0) + remainder);
    }

    return allocated;
  }

  private record SourceMeta(
      Long workspaceId, String sourceType, boolean onlyPublic, List<Long> allowedDocIds) {}
}
