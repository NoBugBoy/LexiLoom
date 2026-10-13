package com.knowflow.application.ai.gateway.rerank;

import com.knowflow.application.ai.enums.RerankStrategyEnum;
import com.knowflow.application.ai.model.search.DocChunkDocument;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 测试与断网降级 Mock 重排适配器 */
@Component
public class MockRerankAdapter implements RerankGateway {

  @Override
  public List<ScoredDocChunkVO> rerank(RerankCommand command) {
    if (command == null || command.candidates().isEmpty()) {
      return List.of();
    }
    List<DocChunkDocument> list = command.candidates();
    int limit = Math.min(list.size(), command.topK());
    List<ScoredDocChunkVO> results = new ArrayList<>(limit);

    for (int i = 0; i < limit; i++) {
      DocChunkDocument doc = list.get(i);
      results.add(ScoredDocChunkVO.of(doc, 0.95 - (i * 0.05), i + 1));
    }
    return results;
  }

  @Override
  public boolean isEnabled() {
    return true;
  }

  @Override
  public RerankStrategyEnum getStrategy() {
    return RerankStrategyEnum.MOCK;
  }
}
