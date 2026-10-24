package io.lexiloom.application.ai.search.strategy;

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

/** ES 原生单请求双路混合检索策略 (Single-Request Native Hybrid Search) */
@Slf4j
@Component
@RequiredArgsConstructor
public class NativeSingleRequestRetrievalStrategy {

  private final DocChunkSearchRepository docChunkSearchRepository;
  private final RerankGateway rerankGateway;

  public List<ScoredDocChunkVO> execute(
      HybridSearchCommand command,
      float[] vector,
      int targetTopK,
      RerankStrategyEnum strategy,
      double minScoreThreshold,
      int maxChunksPerDocument) {

    int candidateLimit = Math.max(targetTopK * 5, 50);
    long retrievalStart = System.currentTimeMillis();
    LexicalQuery lexicalQuery = LexicalQuery.of(command.query());
    List<DocChunkDocument> nativeCandidates =
        docChunkSearchRepository.searchNativeHybrid(
            command, lexicalQuery, vector, command.workspaceId(), false, null, candidateLimit);
    long retrievalCost = System.currentTimeMillis() - retrievalStart;
    RagTraceContext traceContext = RagTraceHolder.get();
    if (traceContext != null) {
      traceContext.recordRetrievalTime(retrievalCost);
    }

    log.info(
        "【HybridSearch】ES 原生单请求双路混合初筛完成: query=[{}], workspaceId={}, 候选切片数={}",
        command.query(),
        command.workspaceId(),
        nativeCandidates.size());

    if (nativeCandidates.isEmpty()) {
      return List.of();
    }

    if (strategy != null && rerankGateway != null && strategy != RerankStrategyEnum.NONE) {
      RerankCommand rerankCommand =
          RerankCommand.of(
              command.query(), nativeCandidates, Math.max(targetTopK, nativeCandidates.size()));
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

    List<ScoredDocChunkVO> fallbackList = new ArrayList<>(nativeCandidates.size());
    Map<String, Double> normalizedBm25 = CandidateMath.normalizeBm25Scores(nativeCandidates);
    for (int i = 0; i < nativeCandidates.size(); i++) {
      DocChunkDocument doc = nativeCandidates.get(i);
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
      fallbackList.add(ScoredDocChunkVO.of(doc, score, i + 1, "NATIVE_HYBRID"));
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
}
