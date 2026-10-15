package io.lexiloom.application.ai.gateway.rerank;

import io.lexiloom.application.ai.model.search.DocChunkDocument;
import java.util.List;

/** 候选切块重排命令 (不可变 record) */
public record RerankCommand(String query, List<DocChunkDocument> candidates, int topK) {

  public RerankCommand {
    candidates = (candidates == null) ? List.of() : List.copyOf(candidates);
    topK = topK > 0 ? topK : 5;
  }

  public static RerankCommand of(String query, List<DocChunkDocument> candidates, int topK) {
    return new RerankCommand(query, candidates, topK);
  }
}
