package io.lexiloom.application.ai.search.domain;

import io.lexiloom.application.ai.gateway.rerank.ScoredDocChunkVO;
import io.lexiloom.application.ai.model.search.DocChunkDocument;

/**
 * 候选切片评估包装项 (值对象)
 *
 * @param chunk ES 原始切块文档
 * @param displayScore 对外展示得分 (真实余弦分或精排模型打分)
 * @param sortScore 综合排序打分 (含 RRF 排位平滑及意图对齐增益)
 * @param originalRank 初筛原始排位
 * @param recallSource 召回源标记 (如 "BM25", "KNN", "HYBRID", "RERANK")
 * @param cosineSimilarity 真实向量余弦相似度
 * @param rerankScore 精排模型打分
 */
public record CandidateItem(
    DocChunkDocument chunk,
    double displayScore,
    double sortScore,
    int originalRank,
    String recallSource,
    Double cosineSimilarity,
    Double rerankScore) {

  public ScoredDocChunkVO toScoredVO() {
    return ScoredDocChunkVO.of(chunk, displayScore, originalRank, recallSource);
  }
}
