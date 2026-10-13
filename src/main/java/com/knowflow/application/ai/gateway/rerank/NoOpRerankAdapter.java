package com.knowflow.application.ai.gateway.rerank;

import com.knowflow.application.ai.enums.RerankStrategyEnum;
import com.knowflow.application.ai.model.search.DocChunkDocument;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 默认透传 / 关闭重排适配器 (NoOpRerankAdapter)
 *
 * <p>零外部 API 调用与零计算开销，直接对双路召回候选集截取 Top-K 返回。
 */
@Component
public class NoOpRerankAdapter implements RerankGateway {

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
      // 赋予递减模拟相关度 (1.00, 0.95, 0.90...)
      double score = Math.max(0.1, 1.0 - (i * 0.05));
      results.add(ScoredDocChunkVO.of(chunk, score, i + 1));
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
