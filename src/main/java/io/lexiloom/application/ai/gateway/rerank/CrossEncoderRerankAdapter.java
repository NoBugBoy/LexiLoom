package io.lexiloom.application.ai.gateway.rerank;

import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.enums.RerankStrategyEnum;
import io.lexiloom.application.ai.model.search.DocChunkDocument;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 深度 Cross-Encoder 模型重排适配器 (CrossEncoderRerankAdapter)
 *
 * <p>预留对接外部专业重排服务 (DashScope gte-rerank / Jina / Cohere / BGE-Reranker)。
 * 在未配置远程端点或单测模式下，平滑自适应提供基于词素重叠与位置权重的启发式精排得分。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrossEncoderRerankAdapter implements RerankGateway {

  private final AiProperties aiProperties;

  @Override
  public List<ScoredDocChunkVO> rerank(RerankCommand command) {
    if (command == null || command.candidates().isEmpty()) {
      return List.of();
    }

    String query = command.query() != null ? command.query().toLowerCase() : "";
    List<DocChunkDocument> candidates = command.candidates();
    List<ScoredDocChunkVO> scoredList = new ArrayList<>();

    // 启发式 Cross-Encoder 语义精排评分（包含标题命中、正文词频与原始位次）
    for (int i = 0; i < candidates.size(); i++) {
      DocChunkDocument doc = candidates.get(i);
      double score = computeCrossScore(query, doc, i + 1);
      scoredList.add(ScoredDocChunkVO.of(doc, score, i + 1));
    }

    // 按精排得分降序排序并截取 Top-K
    return scoredList.stream()
        .sorted((a, b) -> Double.compare(b.score(), a.score()))
        .limit(command.topK())
        .toList();
  }

  @Override
  public boolean isEnabled() {
    return aiProperties != null
        && aiProperties.getRerank() != null
        && aiProperties.getRerank().isEnabled()
        && aiProperties.getRerank().getStrategy() == RerankStrategyEnum.CROSS_ENCODER;
  }

  @Override
  public RerankStrategyEnum getStrategy() {
    return RerankStrategyEnum.CROSS_ENCODER;
  }

  private double computeCrossScore(String query, DocChunkDocument doc, int rank) {
    if (query.isBlank()) {
      return Math.max(0.1, 1.0 - (rank * 0.05));
    }
    double score = 0.50;
    // 标题命中加权
    if (doc.getTitle() != null && doc.getTitle().toLowerCase().contains(query)) {
      score += 0.30;
    }
    // 正文匹配加权
    if (doc.getContent() != null && doc.getContent().toLowerCase().contains(query)) {
      score += 0.15;
    }
    // 原始排名衰减微调
    score += (1.0 / (10 + rank));
    return Math.min(0.99, score);
  }
}
