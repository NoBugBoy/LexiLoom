package io.lexiloom.application.ai.trace.model;

/**
 * RAG 问答链路阶段耗时矩阵 (不可变 record)
 *
 * @param embeddingMs 问题语义向量化耗时 (ms)
 * @param retrievalMs ES 双路初筛耗时 (BM25 + kNN) (ms)
 * @param rerankMs 语义重排耗时 (ms，未启用时为 0)
 * @param generationMs LLM 模型生成耗时 (ms)
 * @param totalMs 端到端总耗时 (ms)
 */
public record TraceLatency(
    long embeddingMs, long retrievalMs, long rerankMs, long generationMs, long totalMs) {}
