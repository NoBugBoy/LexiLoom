package io.lexiloom.application.ai.search.strategy;

import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.enums.RerankStrategyEnum;
import io.lexiloom.application.ai.gateway.rerank.RerankCommand;
import io.lexiloom.application.ai.gateway.rerank.RerankGateway;
import io.lexiloom.application.ai.gateway.rerank.ScoredDocChunkVO;
import io.lexiloom.application.ai.model.search.DocChunkDocument;
import io.lexiloom.application.ai.search.HybridSearchCommand;
import io.lexiloom.application.ai.search.domain.CandidateMath;
import io.lexiloom.application.ai.search.domain.CandidatePool;
import io.lexiloom.application.ai.search.domain.LexicalQuery;
import io.lexiloom.application.ai.search.domain.WorkspaceQuotaPlan;
import io.lexiloom.application.ai.search.domain.WorkspaceSource;
import io.lexiloom.application.ai.search.repository.DocChunkSearchRepository;
import io.lexiloom.application.ai.search.topology.WorkspaceTopologyResolver;
import io.lexiloom.application.ai.trace.RagTraceHolder;
import io.lexiloom.application.ai.trace.model.RagTraceContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 多工作空间加权召回、余量自适应补偿与统一 Rerank 精排策略 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiWorkspaceRetrievalStrategy {

  private final DocChunkSearchRepository docChunkSearchRepository;
  private final RerankGateway rerankGateway;
  private final AiProperties aiProperties;
  private final WorkspaceTopologyResolver topologyResolver;

  public List<ScoredDocChunkVO> execute(
      HybridSearchCommand command,
      float[] vector,
      List<WorkspaceSource> sources,
      int targetTopK,
      RerankStrategyEnum strategy,
      double minScoreThreshold,
      int maxChunksPerDocument) {

    Long primaryId = command.workspaceId();
    WorkspaceQuotaPlan quotaPlan =
        WorkspaceQuotaPlan.calculate(targetTopK, command.weights(), sources, primaryId);

    Set<Long> allSpaceIds =
        sources.stream().map(WorkspaceSource::workspaceId).collect(Collectors.toSet());
    allSpaceIds.add(primaryId);
    Map<Long, String> spaceNames = topologyResolver.resolveWorkspaceNames(allSpaceIds);
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

    long retrievalStart = System.currentTimeMillis();
    Map<Long, List<DocChunkDocument>> selectedChunksBySpace = new HashMap<>();
    int totalDeficit = 0;
    LexicalQuery lexicalQuery = LexicalQuery.of(command.query());

    for (WorkspaceSource source : sources) {
      if (source.workspaceId().equals(primaryId)) {
        continue;
      }
      String sourceName =
          spaceNames.getOrDefault(source.workspaceId(), "空间#" + source.workspaceId());
      String sourceDesc = source.isExplicit() ? "横向关联空间" : "直系子空间";

      int quota = quotaPlan.getQuota(source.workspaceId());
      if (quota <= 0) {
        selectedChunksBySpace.put(source.workspaceId(), List.of());
        continue;
      }

      if (source.hasNoAllowedDocs()) {
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
      List<DocChunkDocument> merged;
      if (isNativeHybridSearchEnabled(command) && vector != null && vector.length > 0) {
        merged =
            docChunkSearchRepository.searchNativeHybrid(
                command,
                lexicalQuery,
                vector,
                source.workspaceId(),
                source.onlyPublic(),
                source.allowedDocIds(),
                candidateLimit);
        log.info(
            "【HybridSearch】从关联空间 [{}] 原生检索完成: 召回候选数={}, 实际采用切片数={}/{}",
            sourceName,
            merged.size(),
            Math.min(quota, merged.size()),
            quota);
      } else {
        List<DocChunkDocument> bm25 =
            docChunkSearchRepository.searchLexical(
                command,
                lexicalQuery,
                source.workspaceId(),
                source.onlyPublic(),
                source.allowedDocIds(),
                candidateLimit);
        List<DocChunkDocument> knn =
            (vector != null && vector.length > 0)
                ? docChunkSearchRepository.searchVector(
                    command,
                    vector,
                    source.workspaceId(),
                    source.onlyPublic(),
                    source.allowedDocIds(),
                    candidateLimit)
                : List.of();

        merged = CandidateMath.interleaveAndDeduplicate(knn, bm25);
        log.info(
            "【HybridSearch】从关联空间 [{}] 检索完成: 召回候选数(BM25={}, kNN={}), 实际采用切片数={}/{}",
            sourceName,
            bm25.size(),
            knn.size(),
            Math.min(quota, merged.size()),
            quota);
      }
      int actual = Math.min(quota, merged.size());
      selectedChunksBySpace.put(source.workspaceId(), merged.subList(0, actual));

      if (actual < quota) {
        totalDeficit += (quota - actual);
      }
    }

    // 2. 主空间召回：基础配额 + 关联空间缺额自适应补偿
    int basePrimaryQuota = quotaPlan.getQuota(primaryId);
    int primaryQuota = quotaPlan.calculateCompensatedPrimaryQuota(totalDeficit);
    log.info(
        "【HybridSearch】检索从主空间 [{}] 中同步检索: workspaceId={}, 基础配额={}, 获得缺额补偿={}, 实际配额={}",
        primaryName,
        primaryId,
        basePrimaryQuota,
        totalDeficit,
        primaryQuota);

    int primaryCandidateLimit = Math.max(primaryQuota * 4, Math.max(targetTopK * 4, 50));
    List<DocChunkDocument> primaryMerged;
    if (isNativeHybridSearchEnabled(command) && vector != null && vector.length > 0) {
      primaryMerged =
          docChunkSearchRepository.searchNativeHybrid(
              command, lexicalQuery, vector, primaryId, false, null, primaryCandidateLimit);
      log.info(
          "【HybridSearch】从主空间 [{}] 原生检索完成: 召回候选数={}, 实际采用切片数={}/{}",
          primaryName,
          primaryMerged.size(),
          Math.min(primaryQuota, primaryMerged.size()),
          primaryQuota);
    } else {
      List<DocChunkDocument> primaryBm25 =
          docChunkSearchRepository.searchLexical(
              command, lexicalQuery, primaryId, false, null, primaryCandidateLimit);
      List<DocChunkDocument> primaryKnn =
          (vector != null && vector.length > 0)
              ? docChunkSearchRepository.searchVector(
                  command, vector, primaryId, false, null, primaryCandidateLimit)
              : List.of();

      primaryMerged = CandidateMath.interleaveAndDeduplicate(primaryKnn, primaryBm25);
      log.info(
          "【HybridSearch】从主空间 [{}] 检索完成: 召回候选数(BM25={}, kNN={}), 实际采用切片数={}/{}",
          primaryName,
          primaryBm25.size(),
          primaryKnn.size(),
          Math.min(primaryQuota, primaryMerged.size()),
          primaryQuota);
    }
    int primaryActual = Math.min(primaryQuota, primaryMerged.size());
    selectedChunksBySpace.put(primaryId, primaryMerged.subList(0, primaryActual));

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

    long retrievalCost = System.currentTimeMillis() - retrievalStart;
    RagTraceContext traceContext = RagTraceHolder.get();
    if (traceContext != null) {
      traceContext.recordRetrievalTime(retrievalCost);
    }

    log.info(
        "【HybridSearch】跨空间多源联合检索初筛汇总: 共有 {} 个空间参与并命中候选切片 (总候选切片数={})",
        selectedChunksBySpace.size(),
        allCandidates.size());

    if (allCandidates.isEmpty()) {
      return List.of();
    }

    // 4. 统一 Rerank 精排：多源候选池统一送入模型重排
    if (strategy != null && rerankGateway != null && strategy != RerankStrategyEnum.NONE) {
      RerankCommand rerankCommand =
          RerankCommand.of(
              command.query(), allCandidates, Math.max(targetTopK, allCandidates.size()));
      long rerankStart = System.currentTimeMillis();
      List<ScoredDocChunkVO> rerankedResults = rerankGateway.rerank(rerankCommand);
      if (traceContext != null) {
        traceContext.recordRerankTime(System.currentTimeMillis() - rerankStart);
        traceContext.recordRerankStrategy(strategy.name());
      }
      return CandidatePool.selectFinalResults(
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
    Map<String, Double> normalizedBm25 = CandidateMath.normalizeBm25Scores(allCandidates);
    for (int i = 0; i < allCandidates.size(); i++) {
      DocChunkDocument doc = allCandidates.get(i);
      Double cos = CandidateMath.cosineSimilarity(vector, doc.getContentVector());
      Double bm25Norm = normalizedBm25.get(doc.getId());
      double score;
      if (cos != null && bm25Norm != null) {
        score = Math.max(cos, bm25Norm);
      } else if (cos != null) {
        score = cos;
      } else if (bm25Norm != null) {
        score = bm25Norm;
      } else {
        score = 1.0 / (60.0 + (i + 1));
      }
      fallbackList.add(ScoredDocChunkVO.of(doc, score, i + 1, "HYBRID"));
    }
    return CandidatePool.selectFinalResults(
        fallbackList,
        command.query(),
        vector,
        strategy,
        minScoreThreshold,
        maxChunksPerDocument,
        targetTopK);
  }

  private boolean isNativeHybridSearchEnabled(HybridSearchCommand command) {
    if (command != null && command.nativeHybridSearch() != null) {
      return command.nativeHybridSearch();
    }
    return aiProperties != null
        && aiProperties.getRag() != null
        && aiProperties.getRag().isNativeHybridSearch();
  }
}
