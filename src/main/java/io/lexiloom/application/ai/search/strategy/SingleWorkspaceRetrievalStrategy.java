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
import io.lexiloom.application.ai.search.repository.DocChunkSearchRepository;
import io.lexiloom.application.ai.trace.RagTraceHolder;
import io.lexiloom.application.ai.trace.model.RagTraceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 单工作空间混合检索执行策略 (保持 100% 向后兼容) */
@Slf4j
@Component
@RequiredArgsConstructor
public class SingleWorkspaceRetrievalStrategy {

  private final DocChunkSearchRepository docChunkSearchRepository;
  private final RerankGateway rerankGateway;
  private final AiProperties aiProperties;
  private final NativeSingleRequestRetrievalStrategy nativeStrategy;
  private final QuotaHybridFusionExecutor quotaFusionExecutor;

  public List<ScoredDocChunkVO> execute(
      HybridSearchCommand command,
      float[] vector,
      int targetTopK,
      RerankStrategyEnum strategy,
      double minScoreThreshold,
      int maxChunksPerDocument) {

    int candidateLimit = Math.max(targetTopK * 5, 50);

    // 若启用了 ES 8.x 原生单请求双路混合检索且提供了有效向量，走单次复合 NativeQuery (减少 50% 网络 RTT)
    if (isNativeHybridSearchEnabled(command) && vector != null && vector.length > 0) {
      return nativeStrategy.execute(
          command, vector, targetTopK, strategy, minScoreThreshold, maxChunksPerDocument);
    }

    long retrievalStart = System.currentTimeMillis();
    LexicalQuery lexicalQuery = LexicalQuery.of(command.query());

    // 1. 词法全文召回 (BM25 + IK 分词)
    List<DocChunkDocument> bm25Candidates =
        docChunkSearchRepository.searchLexical(
            command, lexicalQuery, command.workspaceId(), false, null, candidateLimit);

    // 2. 稠密向量召回 (ES 8.x kNN Dense Vector)
    List<DocChunkDocument> knnCandidates =
        (vector != null && vector.length > 0)
            ? docChunkSearchRepository.searchVector(
                command, vector, command.workspaceId(), false, null, candidateLimit)
            : List.of();

    long retrievalCost = System.currentTimeMillis() - retrievalStart;
    RagTraceContext traceContext = RagTraceHolder.get();
    if (traceContext != null) {
      traceContext.recordRetrievalTime(retrievalCost);
    }

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
          CandidateMath.interleaveRaw(bm25Candidates, knnCandidates);

      RerankCommand rerankCommand =
          RerankCommand.of(
              command.query(),
              candidatesForRerank,
              Math.max(targetTopK, candidatesForRerank.size()));
      long rerankStart = System.currentTimeMillis();
      List<ScoredDocChunkVO> rerankedResults =
          rerankGateway != null ? rerankGateway.rerank(rerankCommand) : List.of();
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
    } else if (knnCandidates.isEmpty()) {
      Map<String, Double> normalizedBm25 = CandidateMath.normalizeBm25Scores(bm25Candidates);
      RerankCommand rerankCommand =
          RerankCommand.of(
              command.query(), bm25Candidates, Math.max(targetTopK, bm25Candidates.size()));
      long rerankStart = System.currentTimeMillis();
      List<ScoredDocChunkVO> rerankedResults =
          rerankGateway != null ? rerankGateway.rerank(rerankCommand) : List.of();
      if (traceContext != null) {
        traceContext.recordRerankTime(System.currentTimeMillis() - rerankStart);
        if (strategy != null) {
          traceContext.recordRerankStrategy(strategy.name());
        }
      }

      // 当重排未产出得分时，由 BM25 归一化得分回填，避免纯词法查询时因 0 分导致阈值误杀
      List<ScoredDocChunkVO> enriched = new ArrayList<>(rerankedResults.size());
      for (ScoredDocChunkVO vo : rerankedResults) {
        double score = vo.score();
        if (score <= 0.0 && vo.chunk() != null) {
          Double norm = normalizedBm25.get(vo.chunk().getId());
          if (norm != null) {
            score = norm;
          }
        }
        enriched.add(ScoredDocChunkVO.of(vo.chunk(), score, vo.originalRank(), vo.recallSource()));
      }

      return CandidatePool.selectFinalResults(
              enriched,
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
      return quotaFusionExecutor.execute(
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

  private boolean isNativeHybridSearchEnabled(HybridSearchCommand command) {
    if (command != null && command.nativeHybridSearch() != null) {
      return command.nativeHybridSearch();
    }
    return aiProperties != null
        && aiProperties.getRag() != null
        && aiProperties.getRag().isNativeHybridSearch();
  }
}
