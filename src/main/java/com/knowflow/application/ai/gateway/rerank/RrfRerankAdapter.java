package com.knowflow.application.ai.gateway.rerank;

import com.knowflow.application.ai.enums.RerankStrategyEnum;
import com.knowflow.application.ai.model.search.DocChunkDocument;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 纯算法倒数排名融合重排适配器 (RrfRerankAdapter)
 *
 * <p>基于经典倒数排名融合算法 (Reciprocal Rank Fusion, RRF)，常数 k=60。 公式: RRF_Score = 1 / (60 +
 * rank)。纯内存执行，零外部调用开销与网络依赖。
 */
@Component
public class RrfRerankAdapter implements RerankGateway {

  private static final int RRF_K = 60;

  @Override
  public List<ScoredDocChunkVO> rerank(RerankCommand command) {
    if (command == null || command.candidates().isEmpty()) {
      return List.of();
    }

    List<DocChunkDocument> candidates = command.candidates();
    Map<String, RrfScoreItem> scoreMap = new HashMap<>();

    for (int i = 0; i < candidates.size(); i++) {
      DocChunkDocument doc = candidates.get(i);
      String key = doc.getId();
      int rank = i + 1; // 1-indexed
      double contribution = 1.0 / (RRF_K + rank);

      scoreMap.compute(
          key,
          (k, existing) -> {
            if (existing == null) {
              return new RrfScoreItem(doc, contribution, rank);
            } else {
              return new RrfScoreItem(
                  existing.chunk(),
                  existing.score() + contribution,
                  Math.min(existing.firstRank(), rank));
            }
          });
    }

    return scoreMap.values().stream()
        .sorted(Comparator.comparingDouble(RrfScoreItem::score).reversed())
        .limit(command.topK())
        .map(item -> ScoredDocChunkVO.of(item.chunk(), item.score(), item.firstRank()))
        .toList();
  }

  @Override
  public boolean isEnabled() {
    return true;
  }

  @Override
  public RerankStrategyEnum getStrategy() {
    return RerankStrategyEnum.RRF;
  }

  private record RrfScoreItem(DocChunkDocument chunk, double score, int firstRank) {}
}
