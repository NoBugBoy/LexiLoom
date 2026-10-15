package io.lexiloom.application.ai.gateway.rerank;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.model.search.DocChunkDocument;

/** 重排打分后的切块候选对象 (不可变 record) */
public record ScoredDocChunkVO(
    DocChunkDocument chunk, double score, int originalRank, String recallSource) {

  public ScoredDocChunkVO {
    recallSource = StrUtil.blankToDefault(recallSource, "RERANK");
  }

  public ScoredDocChunkVO(DocChunkDocument chunk, double score) {
    this(chunk, score, 0, "RERANK");
  }

  public ScoredDocChunkVO(DocChunkDocument chunk, double score, int originalRank) {
    this(chunk, score, originalRank, "RERANK");
  }

  public static ScoredDocChunkVO of(DocChunkDocument chunk, double score) {
    return new ScoredDocChunkVO(chunk, score, 0, "RERANK");
  }

  public static ScoredDocChunkVO of(DocChunkDocument chunk, double score, int originalRank) {
    return new ScoredDocChunkVO(chunk, score, originalRank, "RERANK");
  }

  public static ScoredDocChunkVO of(
      DocChunkDocument chunk, double score, int originalRank, String recallSource) {
    return new ScoredDocChunkVO(chunk, score, originalRank, recallSource);
  }
}
