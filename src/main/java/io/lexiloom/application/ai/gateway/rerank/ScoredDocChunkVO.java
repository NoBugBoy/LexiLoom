package io.lexiloom.application.ai.gateway.rerank;

import io.lexiloom.application.ai.model.search.DocChunkDocument;

/** 重排打分后的切块候选对象 (不可变 record) */
public record ScoredDocChunkVO(DocChunkDocument chunk, double score, int originalRank) {

  public ScoredDocChunkVO(DocChunkDocument chunk, double score) {
    this(chunk, score, 0);
  }

  public static ScoredDocChunkVO of(DocChunkDocument chunk, double score) {
    return new ScoredDocChunkVO(chunk, score, 0);
  }

  public static ScoredDocChunkVO of(DocChunkDocument chunk, double score, int originalRank) {
    return new ScoredDocChunkVO(chunk, score, originalRank);
  }
}
