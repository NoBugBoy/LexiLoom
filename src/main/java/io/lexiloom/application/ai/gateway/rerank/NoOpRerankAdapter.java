package io.lexiloom.application.ai.gateway.rerank;

import io.lexiloom.application.ai.enums.RerankStrategyEnum;
import io.lexiloom.application.ai.model.search.DocChunkDocument;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 默认透传 / 关闭重排适配器 (NoOpRerankAdapter)
 *
 * <p>零外部 API 调用与零计算开销，仅按双路候选顺序透传，不做任何打分。
 *
 * <p>注意：本适配器<b>不编造相似度分数</b>，透传分数的真实语义相似度由 {@code DefaultHybridSearchQueryService}
 * 在终态选择阶段基于真实向量余弦统一回填，避免模拟分数导致阈值过滤与前端展示失真。
 */
@Component
public class NoOpRerankAdapter implements RerankGateway {

  /** 透传占位分数：表示本适配器不产出任何真实相关度，等待服务层回填真实余弦相似度 */
  public static final double NO_SCORE = 0.0;

  @Override
  public List<ScoredDocChunkVO> rerank(RerankCommand command) {
    if (command == null || command.candidates().isEmpty()) {
      return List.of();
    }
    List<DocChunkDocument> list = command.candidates();
    int limit = Math.min(list.size(), command.topK());
    List<ScoredDocChunkVO> results = new ArrayList<>(limit);

    for (int i = 0; i < limit; i++) {
      DocChunkDocument chunk = list.get(i);
      results.add(ScoredDocChunkVO.of(chunk, NO_SCORE, i + 1));
    }
    return results;
  }

  @Override
  public boolean isEnabled() {
    return false;
  }

  @Override
  public RerankStrategyEnum getStrategy() {
    return RerankStrategyEnum.NONE;
  }
}
