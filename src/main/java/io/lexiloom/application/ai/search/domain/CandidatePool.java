package io.lexiloom.application.ai.search.domain;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.enums.RerankStrategyEnum;
import io.lexiloom.application.ai.gateway.rerank.ScoredDocChunkVO;
import io.lexiloom.application.ai.model.search.DocChunkDocument;
import io.lexiloom.application.ai.trace.RagTraceHolder;
import io.lexiloom.application.ai.trace.model.CandidateTraceItem;
import io.lexiloom.application.ai.trace.model.FilterDecision;
import io.lexiloom.application.ai.trace.model.FilterReason;
import io.lexiloom.application.ai.trace.model.RagTraceContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * 候选切片评估池聚合根 (CandidatePool)
 *
 * <p>负责候选切片的去重、真实语义余弦相似度注入、意图感知加权、相似度阈值过滤、 单文档多样性限制截取（Diversity Truncation）、Top-K 兜底补齐（Backfill）以及
 * RAG 链路追踪记录。
 */
@Slf4j
public record CandidatePool(List<CandidateItem> candidateItems) {

  public CandidatePool {
    candidateItems = List.copyOf(candidateItems);
  }

  public static List<ScoredDocChunkVO> selectFinalResults(
      List<ScoredDocChunkVO> rerankedResults,
      String query,
      float[] queryVector,
      RerankStrategyEnum strategy,
      double minScoreThreshold,
      int maxChunksPerDocument,
      int targetTopK) {

    Set<String> seenChunkIds = new HashSet<>();
    List<CandidateItem> candidateItems = new ArrayList<>(rerankedResults.size());
    boolean isTableIntent = CandidateMath.isTableQuery(query);
    RagTraceContext traceContext = RagTraceHolder.get();

    for (ScoredDocChunkVO item : rerankedResults) {
      if (item == null || item.chunk() == null || item.chunk().getId() == null) {
        continue;
      }
      DocChunkDocument chunk = item.chunk();
      Double cosine = CandidateMath.cosineSimilarity(queryVector, chunk.getContentVector());
      Float rawScore = chunk.getRawScore();
      String source = StrUtil.blankToDefault(item.recallSource(), "RERANK");

      if (!seenChunkIds.add(chunk.getId())) {
        if (traceContext != null) {
          traceContext.recordCandidate(
              new CandidateTraceItem(
                  chunk.getId(),
                  chunk.getDocumentId(),
                  chunk.getTitle(),
                  source,
                  rawScore,
                  null,
                  cosine,
                  item.score(),
                  item.originalRank(),
                  FilterDecision.REJECTED,
                  FilterReason.DUPLICATE_CHUNK));
        }
        continue;
      }

      double baseScore;
      if (strategy == RerankStrategyEnum.CROSS_ENCODER && item.score() > 0.0) {
        baseScore = item.score();
      } else {
        baseScore = (cosine != null) ? cosine : item.score();
      }
      // 对于检索条件包含了表和table关键字的并且召回chunk是表格类型的chunk额外给一定倍数，提高排名
      boolean isTable = isTableIntent && CandidateMath.isTableChunk(chunk);
      double effectiveThreshold = isTable ? minScoreThreshold * 0.65 : minScoreThreshold;
      if (strategy != RerankStrategyEnum.RRF
          && (strategy == RerankStrategyEnum.CROSS_ENCODER || cosine != null)) {
        if (baseScore < effectiveThreshold) {
          if (traceContext != null) {
            traceContext.recordCandidate(
                new CandidateTraceItem(
                    chunk.getId(),
                    chunk.getDocumentId(),
                    chunk.getTitle(),
                    source,
                    rawScore,
                    null,
                    cosine,
                    item.score(),
                    item.originalRank(),
                    FilterDecision.REJECTED,
                    FilterReason.BELOW_SIMILARITY_THRESHOLD));
          }
          continue;
        }
      }

      double rankBonus = 1.0 / (60.0 + item.originalRank());
      double intentBonus = isTable ? 0.05 : 0.0;
      double sortScore = baseScore + rankBonus + intentBonus;

      candidateItems.add(
          new CandidateItem(
              chunk, baseScore, sortScore, item.originalRank(), source, cosine, item.score()));
    }

    // 按综合排序得分降序排列（分数相同时保持原始顺序）
    candidateItems.sort(Comparator.comparingDouble(CandidateItem::sortScore).reversed());

    List<ScoredDocChunkVO> selected = new ArrayList<>(Math.min(targetTopK, candidateItems.size()));
    List<CandidateItem> backfillPool = new ArrayList<>();
    Map<String, Integer> perDocumentCount = new HashMap<>();
    int diversityTruncated = 0;

    Set<String> acceptedChunkIds = new HashSet<>();
    Set<String> quotaExceededChunkIds = new HashSet<>();

    // 第一阶段：多样性优先（按 maxChunksPerDocument 限制单文档初选量）
    for (CandidateItem item : candidateItems) {
      if (selected.size() >= targetTopK) {
        quotaExceededChunkIds.add(item.chunk().getId());
        continue;
      }
      String documentId = item.chunk().getDocumentId();
      if (maxChunksPerDocument <= 0 || StrUtil.isBlank(documentId)) {
        selected.add(item.toScoredVO());
        acceptedChunkIds.add(item.chunk().getId());
        continue;
      }
      int used = perDocumentCount.getOrDefault(documentId, 0);
      if (used < maxChunksPerDocument) {
        perDocumentCount.put(documentId, used + 1);
        selected.add(item.toScoredVO());
        acceptedChunkIds.add(item.chunk().getId());
      } else {
        backfillPool.add(item);
        diversityTruncated++;
      }
    }

    // 第二阶段：目标 Top-K 兜底补齐（Backfill）
    Set<String> backfilledChunkIds = new HashSet<>();
    if (selected.size() < targetTopK && !backfillPool.isEmpty()) {
      int beforeCount = selected.size();
      for (CandidateItem item : backfillPool) {
        if (selected.size() >= targetTopK) {
          break;
        }
        selected.add(item.toScoredVO());
        backfilledChunkIds.add(item.chunk().getId());
      }
      log.info(
          "【HybridSearch】多样性回填生效: 首轮仅选出 {} 条，从备选池按分数补齐 {} 条同文档切片，最终输出 {} 条",
          beforeCount,
          selected.size() - beforeCount,
          selected.size());
    } else if (diversityTruncated > 0) {
      log.info("【HybridSearch】文档级多样性上限生效: 单文档最多 {} 条，多余候选已排除", maxChunksPerDocument);
    }

    // 第三阶段：记录全链路追踪详情
    if (traceContext != null) {
      Set<String> backfillPoolIds =
          backfillPool.stream().map(b -> b.chunk().getId()).collect(Collectors.toSet());
      for (CandidateItem item : candidateItems) {
        String chunkId = item.chunk().getId();
        FilterDecision decision;
        FilterReason reason;
        if (acceptedChunkIds.contains(chunkId)) {
          decision = FilterDecision.ACCEPTED;
          reason = FilterReason.NONE;
        } else if (backfilledChunkIds.contains(chunkId)) {
          decision = FilterDecision.BACKFILL;
          reason = FilterReason.NONE;
        } else if (backfillPoolIds.contains(chunkId)) {
          decision = FilterDecision.REJECTED;
          reason = FilterReason.DIVERSITY_TRUNCATED;
        } else if (quotaExceededChunkIds.contains(chunkId)) {
          decision = FilterDecision.REJECTED;
          reason = FilterReason.TOPK_QUOTA_EXCEEDED;
        } else {
          decision = FilterDecision.REJECTED;
          reason = FilterReason.NONE;
        }

        traceContext.recordCandidate(
            new CandidateTraceItem(
                chunkId,
                item.chunk().getDocumentId(),
                item.chunk().getTitle(),
                item.recallSource(),
                item.chunk().getRawScore(),
                null,
                item.cosineSimilarity(),
                item.rerankScore(),
                item.originalRank(),
                decision,
                reason));
      }
    }

    return List.copyOf(selected);
  }
}
