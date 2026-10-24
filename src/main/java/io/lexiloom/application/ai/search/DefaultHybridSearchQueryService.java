package io.lexiloom.application.ai.search;

import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.enums.RerankStrategyEnum;
import io.lexiloom.application.ai.gateway.embedding.EmbeddingGateway;
import io.lexiloom.application.ai.gateway.rerank.RerankGateway;
import io.lexiloom.application.ai.gateway.rerank.ScoredDocChunkVO;
import io.lexiloom.application.ai.search.domain.WorkspaceSource;
import io.lexiloom.application.ai.search.repository.DocChunkSearchRepository;
import io.lexiloom.application.ai.search.repository.ElasticsearchDocChunkSearchRepository;
import io.lexiloom.application.ai.search.strategy.MultiWorkspaceRetrievalStrategy;
import io.lexiloom.application.ai.search.strategy.NativeSingleRequestRetrievalStrategy;
import io.lexiloom.application.ai.search.strategy.QuotaHybridFusionExecutor;
import io.lexiloom.application.ai.search.strategy.SingleWorkspaceRetrievalStrategy;
import io.lexiloom.application.ai.search.topology.WorkspaceTopologyResolver;
import io.lexiloom.application.ai.trace.RagTraceHolder;
import io.lexiloom.application.ai.trace.model.RagTraceContext;
import io.lexiloom.application.document.api.DocumentQueryApi;
import io.lexiloom.application.user.api.WorkSpaceQueryService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * 双路混合召回与精排服务默认实现 (DefaultHybridSearchQueryService)
 *
 * <p>本服务作为混合检索核心用例编排者 (Facade / UseCase Orchestrator)：
 *
 * <ol>
 *   <li>问题向量化：基于 {@link EmbeddingGateway} 获取 1536 维 dense vector；
 *   <li>多空间拓扑与安全围栏：委派 {@link WorkspaceTopologyResolver} 探测主空间与关联子空间；
 *   <li>策略路由与检索：委派 {@link SingleWorkspaceRetrievalStrategy} 与 {@link
 *       MultiWorkspaceRetrievalStrategy} 执行 ES 8.x 双路检索（词法 BM25 + kNN 向量）或单请求 Native Hybrid 检索；
 *   <li>精排与终态选择：通过策略委派 {@link RerankGateway} 并由 {@link
 *       io.lexiloom.application.ai.search.domain.CandidatePool} 执行真实语义余弦回填、意图加权、单文档多样性限制截取与 Top-K
 *       兜底补齐。
 * </ol>
 */
@Slf4j
@Service
@Primary
public class DefaultHybridSearchQueryService implements HybridSearchQueryService {

  private final EmbeddingGateway embeddingGateway;
  private final RerankGateway rerankGateway;
  private final AiProperties aiProperties;
  private final WorkspaceTopologyResolver topologyResolver;
  private final SingleWorkspaceRetrievalStrategy singleStrategy;
  private final MultiWorkspaceRetrievalStrategy multiStrategy;

  @Autowired
  public DefaultHybridSearchQueryService(
      EmbeddingGateway embeddingGateway,
      RerankGateway rerankGateway,
      AiProperties aiProperties,
      WorkspaceTopologyResolver topologyResolver,
      SingleWorkspaceRetrievalStrategy singleStrategy,
      MultiWorkspaceRetrievalStrategy multiStrategy) {
    this.embeddingGateway = embeddingGateway;
    this.rerankGateway = rerankGateway;
    this.aiProperties = aiProperties;
    this.topologyResolver = topologyResolver;
    this.singleStrategy = singleStrategy;
    this.multiStrategy = multiStrategy;
  }

  public DefaultHybridSearchQueryService(
      ElasticsearchOperations elasticsearchOperations,
      EmbeddingGateway embeddingGateway,
      RerankGateway rerankGateway,
      AiProperties aiProperties,
      @Nullable WorkSpaceQueryService workSpaceQueryService,
      @Nullable DocumentQueryApi documentQueryApi) {
    this(
        embeddingGateway,
        rerankGateway,
        aiProperties,
        new WorkspaceTopologyResolver(workSpaceQueryService, documentQueryApi),
        createSingleStrategy(elasticsearchOperations, rerankGateway, aiProperties),
        createMultiStrategy(
            elasticsearchOperations,
            rerankGateway,
            aiProperties,
            new WorkspaceTopologyResolver(workSpaceQueryService, documentQueryApi)));
  }

  public DefaultHybridSearchQueryService(
      ElasticsearchOperations elasticsearchOperations,
      EmbeddingGateway embeddingGateway,
      RerankGateway rerankGateway,
      AiProperties aiProperties,
      @Nullable WorkSpaceQueryService workSpaceQueryService) {
    this(
        elasticsearchOperations,
        embeddingGateway,
        rerankGateway,
        aiProperties,
        workSpaceQueryService,
        null);
  }

  public DefaultHybridSearchQueryService(
      ElasticsearchOperations elasticsearchOperations,
      EmbeddingGateway embeddingGateway,
      RerankGateway rerankGateway,
      AiProperties aiProperties) {
    this(elasticsearchOperations, embeddingGateway, rerankGateway, aiProperties, null, null);
  }

  private static SingleWorkspaceRetrievalStrategy createSingleStrategy(
      ElasticsearchOperations elasticsearchOperations,
      RerankGateway rerankGateway,
      AiProperties aiProperties) {
    DocChunkSearchRepository searchRepository =
        new ElasticsearchDocChunkSearchRepository(elasticsearchOperations);
    QuotaHybridFusionExecutor quotaFusionExecutor =
        new QuotaHybridFusionExecutor(rerankGateway, aiProperties);
    NativeSingleRequestRetrievalStrategy nativeStrategy =
        new NativeSingleRequestRetrievalStrategy(searchRepository, rerankGateway);
    return new SingleWorkspaceRetrievalStrategy(
        searchRepository, rerankGateway, aiProperties, nativeStrategy, quotaFusionExecutor);
  }

  private static MultiWorkspaceRetrievalStrategy createMultiStrategy(
      ElasticsearchOperations elasticsearchOperations,
      RerankGateway rerankGateway,
      AiProperties aiProperties,
      WorkspaceTopologyResolver topologyResolver) {
    DocChunkSearchRepository searchRepository =
        new ElasticsearchDocChunkSearchRepository(elasticsearchOperations);
    return new MultiWorkspaceRetrievalStrategy(
        searchRepository, rerankGateway, aiProperties, topologyResolver);
  }

  @Override
  public List<ScoredDocChunkVO> hybridSearch(HybridSearchCommand command) {
    if (command == null) {
      return List.of();
    }

    long startTime = System.currentTimeMillis();
    RagTraceContext traceContext = RagTraceHolder.get();

    // 1. 获取问题向量 (优先使用入参自带的向量，无则调用 EmbeddingGateway 计算)
    float[] vector = command.queryVector();
    if (vector == null || vector.length == 0) {
      try {
        long embStart = System.currentTimeMillis();
        vector = embeddingGateway.embed(command.query());
        if (traceContext != null) {
          traceContext.recordEmbeddingTime(System.currentTimeMillis() - embStart);
        }
      } catch (Exception e) {
        log.warn(
            "【HybridSearch】调用向量网关提取问题向量失败，将回退纯词法召回: query=[{}], error={}",
            command.query(),
            e.getMessage());
      }
    }

    RerankStrategyEnum strategy = rerankGateway != null ? rerankGateway.getStrategy() : null;
    double minScoreThreshold = resolveMinScoreThreshold(command);
    int maxChunksPerDocument = resolveMaxChunksPerDocument();

    // 2. 检查多工作空间拓扑逻辑：若未指定单文档且存在多源配置与权重，走多空间加权召回
    List<WorkspaceSource> sources =
        (command.documentId() == null)
            ? topologyResolver.resolveAvailableSources(command.workspaceId())
            : List.of();
    boolean hasMultiSource = topologyResolver.hasMultiSource(command, sources);
    int targetTopK = resolveTargetTopK(command, hasMultiSource);

    if (sources.size() > 1 && !hasMultiSource) {
      log.info(
          "【HybridSearch】检测到当前空间存在 {} 个关联空间，但调用方未指定关联空间权重，按单空间逻辑仅检索当前主空间: workspaceId={}",
          sources.size() - 1,
          command.workspaceId());
    }

    List<ScoredDocChunkVO> finalResults;
    if (hasMultiSource) {
      finalResults =
          multiStrategy.execute(
              command,
              vector,
              sources,
              targetTopK,
              strategy,
              minScoreThreshold,
              maxChunksPerDocument);
    } else {
      finalResults =
          singleStrategy.execute(
              command, vector, targetTopK, strategy, minScoreThreshold, maxChunksPerDocument);
    }

    long cost = System.currentTimeMillis() - startTime;
    log.info(
        "【HybridSearch】检索与重排完成: query=[{}], 多源={}, 精排输出数={}, 阈值={}, 单文档切片上限={}, 耗时={}ms, 策略={}",
        command.query(),
        hasMultiSource,
        finalResults.size(),
        minScoreThreshold,
        maxChunksPerDocument,
        cost,
        strategy);

    return List.copyOf(finalResults);
  }

  private double resolveMinScoreThreshold(HybridSearchCommand command) {
    if (command.minScore() != null) {
      return command.minScore();
    }
    return (aiProperties != null && aiProperties.getRag() != null)
        ? aiProperties.getRag().getSimilarityThreshold()
        : 0.0;
  }

  private int resolveMaxChunksPerDocument() {
    return (aiProperties != null && aiProperties.getRag() != null)
        ? aiProperties.getRag().getMaxChunksPerDocument()
        : 0;
  }

  private int resolveTargetTopK(HybridSearchCommand command, boolean hasMultiSource) {
    int minTopK =
        (aiProperties != null && aiProperties.getRag() != null)
            ? aiProperties.getRag().getMinTopK()
            : 20;
    int maxTopK =
        (aiProperties != null && aiProperties.getRag() != null)
            ? aiProperties.getRag().getMaxTopK()
            : 50;

    int requested = command.topK();
    if (hasMultiSource) {
      if (requested <= 0 || requested < minTopK) {
        return minTopK;
      }
      return Math.min(requested, maxTopK);
    }

    if (requested > 0) {
      return Math.min(requested, maxTopK);
    }
    if (aiProperties != null && aiProperties.getRag() != null) {
      return aiProperties.getRag().getTopK();
    }
    return minTopK;
  }
}
