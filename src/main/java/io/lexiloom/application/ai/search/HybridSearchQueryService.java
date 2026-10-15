package io.lexiloom.application.ai.search;

import io.lexiloom.application.ai.gateway.rerank.RerankGateway;
import io.lexiloom.application.ai.gateway.rerank.ScoredDocChunkVO;
import java.util.List;

/**
 * 双路混合召回与精排服务契约 (HybridSearchQueryService)
 *
 * <p>基于 Elasticsearch 8.x 实现词法全文检索 (BM25 + IK分词) 与语义稠密向量检索 (kNN Dense Vector) 的并行双路召回，并委派 {@link
 * RerankGateway} 完成多路候选集重排与融合。
 */
public interface HybridSearchQueryService {

  /**
   * 执行双路召回与可选重排
   *
   * @param command 混合检索命令
   * @return 经过精排加权与排序后的切块候选列表
   */
  List<ScoredDocChunkVO> hybridSearch(HybridSearchCommand command);
}
