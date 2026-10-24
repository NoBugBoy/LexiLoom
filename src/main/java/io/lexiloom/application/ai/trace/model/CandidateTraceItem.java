package io.lexiloom.application.ai.trace.model;

/**
 * 候选分片在检索、打分与过滤过程中的详细快照 (不可变 record)
 *
 * @param chunkId 切片唯一 ID
 * @param documentId 归属文档 ID
 * @param docTitle 归属文档标题
 * @param recallSource 初筛召回来源 (BM25 / KNN / NATIVE_HYBRID)
 * @param rawScore ES 原始检索打分 (Lucene BM25 原始分或 ES kNN 相似分)
 * @param normalizedScore 归一化后得分 (0.40 ~ 0.95)
 * @param cosineSimilarity 与 Query 的真实语义向量余弦相似度
 * @param rerankScore 深度重排打分 (若启用 Rerank)
 * @param initialRank 初筛排位序号 (1-indexed)
 * @param decision 准入决策 (ACCEPTED / REJECTED / BACKFILL)
 * @param rejectReason 过滤或降级归因 (NONE / BELOW_SIMILARITY_THRESHOLD 等)
 */
public record CandidateTraceItem(
    String chunkId,
    String documentId,
    String docTitle,
    String recallSource,
    Float rawScore,
    Double normalizedScore,
    Double cosineSimilarity,
    Double rerankScore,
    int initialRank,
    FilterDecision decision,
    FilterReason rejectReason) {}
