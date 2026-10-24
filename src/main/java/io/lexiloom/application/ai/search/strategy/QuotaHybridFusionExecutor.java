package io.lexiloom.application.ai.search.strategy;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.enums.RerankStrategyEnum;
import io.lexiloom.application.ai.gateway.rerank.RerankCommand;
import io.lexiloom.application.ai.gateway.rerank.RerankGateway;
import io.lexiloom.application.ai.gateway.rerank.ScoredDocChunkVO;
import io.lexiloom.application.ai.model.search.DocChunkDocument;
import io.lexiloom.application.ai.search.HybridSearchCommand;
import io.lexiloom.application.ai.search.domain.CandidateMath;
import io.lexiloom.application.ai.search.domain.CandidatePool;
import io.lexiloom.application.ai.trace.RagTraceHolder;
import io.lexiloom.application.ai.trace.model.CandidateTraceItem;
import io.lexiloom.application.ai.trace.model.FilterDecision;
import io.lexiloom.application.ai.trace.model.FilterReason;
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

/** 配额隔离混合检索执行器：向量精排 Top-N + BM25 词法保底 Top-M */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuotaHybridFusionExecutor {

  private final RerankGateway rerankGateway;
  private final AiProperties aiProperties;

  public List<ScoredDocChunkVO> execute(
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
    RagTraceContext traceContext = RagTraceHolder.get();
    if (!knnCandidates.isEmpty()) {
      RerankCommand rerankCommand =
          RerankCommand.of(
              command.query(), knnCandidates, Math.max(vectorQuota, knnCandidates.size()));
      long rerankStart = System.currentTimeMillis();
      List<ScoredDocChunkVO> rerankedKnn =
          rerankGateway != null ? rerankGateway.rerank(rerankCommand) : List.of();
      if (traceContext != null) {
        traceContext.recordRerankTime(System.currentTimeMillis() - rerankStart);
        if (strategy != null) {
          traceContext.recordRerankStrategy(strategy.name());
        }
      }

      vectorSelected =
          new ArrayList<>(
              CandidatePool.selectFinalResults(
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
    Map<String, Double> normalizedBm25Scores = CandidateMath.normalizeBm25Scores(bm25Candidates);

    Set<String> lexicalAcceptedIds = new HashSet<>();
    Set<String> lexicalBackfilledIds = new HashSet<>();
    Set<String> lexicalQuotaExceededIds = new HashSet<>();

    for (int i = 0; i < bm25Candidates.size(); i++) {
      DocChunkDocument doc = bm25Candidates.get(i);
      if (doc == null || doc.getId() == null) {
        continue;
      }
      if (selectedChunkIds.contains(doc.getId())) {
        if (traceContext != null) {
          traceContext.recordCandidate(
              new CandidateTraceItem(
                  doc.getId(),
                  doc.getDocumentId(),
                  doc.getTitle(),
                  "BM25",
                  doc.getRawScore(),
                  normalizedBm25Scores.get(doc.getId()),
                  CandidateMath.cosineSimilarity(vector, doc.getContentVector()),
                  null,
                  i + 1,
                  FilterDecision.REJECTED,
                  FilterReason.DUPLICATE_CHUNK));
        }
        continue;
      }

      if (lexicalSelected.size() >= targetLexicalCount) {
        lexicalQuotaExceededIds.add(doc.getId());
        continue;
      }

      Double cosine = CandidateMath.cosineSimilarity(vector, doc.getContentVector());
      Double bm25Norm = normalizedBm25Scores.get(doc.getId());

      // 词法保底打分：优先结合归一化 BM25 分数与向量余弦分 (取二者较优者)
      double score;
      if (bm25Norm != null) {
        score = (cosine != null && cosine > bm25Norm) ? cosine : bm25Norm;
      } else if (cosine != null && cosine > 0.0) {
        score = cosine;
      } else {
        score = 1.0 / (60.0 + (i + 1));
      }

      String documentId = doc.getDocumentId();
      ScoredDocChunkVO vo = ScoredDocChunkVO.of(doc, score, lexicalRankCounter++, "BM25");
      if (maxChunksPerDocument <= 0 || StrUtil.isBlank(documentId)) {
        lexicalSelected.add(vo);
        selectedChunkIds.add(doc.getId());
        lexicalAcceptedIds.add(doc.getId());
      } else {
        int currentCount = perDocumentCount.getOrDefault(documentId, 0);
        if (currentCount < maxChunksPerDocument) {
          perDocumentCount.put(documentId, currentCount + 1);
          lexicalSelected.add(vo);
          selectedChunkIds.add(doc.getId());
          lexicalAcceptedIds.add(doc.getId());
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
          lexicalBackfilledIds.add(backfillItem.chunk().getId());
        }
      }
    }

    if (traceContext != null) {
      Set<String> backfillPoolIds =
          lexicalBackfill.stream().map(b -> b.chunk().getId()).collect(Collectors.toSet());
      for (int i = 0; i < bm25Candidates.size(); i++) {
        DocChunkDocument doc = bm25Candidates.get(i);
        if (doc == null || doc.getId() == null) {
          continue;
        }
        String docId = doc.getId();
        if (selectedChunkIds.contains(docId)
            && !lexicalAcceptedIds.contains(docId)
            && !lexicalBackfilledIds.contains(docId)) {
          continue;
        }

        Double cosine = CandidateMath.cosineSimilarity(vector, doc.getContentVector());
        Double bm25Norm = normalizedBm25Scores.get(docId);
        FilterDecision decision;
        FilterReason reason;
        if (lexicalAcceptedIds.contains(docId)) {
          decision = FilterDecision.ACCEPTED;
          reason = FilterReason.NONE;
        } else if (lexicalBackfilledIds.contains(docId)) {
          decision = FilterDecision.BACKFILL;
          reason = FilterReason.NONE;
        } else if (backfillPoolIds.contains(docId)) {
          decision = FilterDecision.REJECTED;
          reason = FilterReason.DIVERSITY_TRUNCATED;
        } else if (lexicalQuotaExceededIds.contains(docId)) {
          decision = FilterDecision.REJECTED;
          reason = FilterReason.TOPK_QUOTA_EXCEEDED;
        } else {
          decision = FilterDecision.REJECTED;
          reason = FilterReason.NONE;
        }

        traceContext.recordCandidate(
            new CandidateTraceItem(
                docId,
                doc.getDocumentId(),
                doc.getTitle(),
                "BM25",
                doc.getRawScore(),
                bm25Norm,
                cosine,
                null,
                i + 1,
                decision,
                reason));
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

  public int resolveVectorTopK(int totalTopK) {
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

  public int resolveLexicalTopK(int totalTopK, int vectorQuota) {
    return Math.max(0, totalTopK - vectorQuota);
  }
}
