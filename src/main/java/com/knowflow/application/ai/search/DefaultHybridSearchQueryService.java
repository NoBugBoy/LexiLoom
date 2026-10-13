package com.knowflow.application.ai.search;

import co.elastic.clients.elasticsearch._types.FieldValue;
import com.knowflow.application.ai.config.AiProperties;
import com.knowflow.application.ai.enums.RerankStrategyEnum;
import com.knowflow.application.ai.gateway.embedding.EmbeddingGateway;
import com.knowflow.application.ai.gateway.rerank.RerankCommand;
import com.knowflow.application.ai.gateway.rerank.RerankGateway;
import com.knowflow.application.ai.gateway.rerank.ScoredDocChunkVO;
import com.knowflow.application.ai.model.search.DocChunkDocument;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

/**
 * 双路混合召回与精排服务默认实现 (DefaultHybridSearchQueryService)
 *
 * <p>核心流程：
 *
 * <ol>
 *   <li>问题向量化：基于 {@link EmbeddingGateway} 获取 1536 维 dense vector；
 *   <li>并行检索：
 *       <ul>
 *         <li>路径 1 (词法全文检索)：基于 IK 分词的 multi_match (title^3, content^2, contextPrefix^1)；
 *         <li>路径 2 (稠密向量检索)：基于 Elasticsearch 8.x kNN 检索 content_vector 向量相似度；
 *       </ul>
 *   <li>多租户过滤：双路检索强制附加 {@code workspaceId} 过滤，杜绝跨租户数据越权；
 *   <li>精排融合：委托 {@link RerankGateway}（NoOp / RRF / CrossEncoder）执行打分截取与去重。
 * </ol>
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class DefaultHybridSearchQueryService implements HybridSearchQueryService {

  private final ElasticsearchOperations elasticsearchOperations;
  private final EmbeddingGateway embeddingGateway;
  private final RerankGateway rerankGateway;
  private final AiProperties aiProperties;

  @Override
  public List<ScoredDocChunkVO> hybridSearch(HybridSearchCommand command) {
    if (command == null) {
      return List.of();
    }

    long startTime = System.currentTimeMillis();
    int targetTopK = command.topK();
    int candidateLimit = Math.max(targetTopK * 2, 10);

    // 1. 获取问题向量 (优先使用入参自带的向量，无则调用 EmbeddingGateway 计算)
    float[] vector = command.queryVector();
    if (vector == null || vector.length == 0) {
      try {
        vector = embeddingGateway.embed(command.query());
      } catch (Exception e) {
        log.warn(
            "【HybridSearch】调用向量网关提取问题向量失败，将回退纯词法召回: query=[{}], error={}",
            command.query(),
            e.getMessage());
      }
    }

    // 2. 路径 1: 词法全文召回 (BM25 + IK 分词)
    List<DocChunkDocument> bm25Candidates = searchLexical(command, candidateLimit);

    // 3. 路径 2: 稠密向量召回 (ES 8.x kNN Dense Vector)
    List<DocChunkDocument> knnCandidates =
        (vector != null && vector.length > 0)
            ? searchVector(command, vector, candidateLimit)
            : List.of();

    log.info(
        "【HybridSearch】双路初筛召回完成: query=[{}], workspaceId={}, BM25候选数={}, kNN候选数={}",
        command.query(),
        command.workspaceId(),
        bm25Candidates.size(),
        knnCandidates.size());

    // 4. 若双路均未命中，直接返回空
    if (bm25Candidates.isEmpty() && knnCandidates.isEmpty()) {
      return List.of();
    }

    // 5. 候选集组装 (根据重排策略决定是否保留双路重叠项)
    List<DocChunkDocument> candidatesForRerank;
    RerankStrategyEnum strategy = rerankGateway != null ? rerankGateway.getStrategy() : null;
    if (strategy == RerankStrategyEnum.RRF) {
      // RRF 算法专门需要多路排名数据进行倒数排名融合累加，保留双路原序列
      candidatesForRerank = new ArrayList<>(bm25Candidates.size() + knnCandidates.size());
      candidatesForRerank.addAll(bm25Candidates);
      candidatesForRerank.addAll(knnCandidates);
    } else {
      // 非 RRF 模式 (NoOp / CrossEncoder / Mock)，执行交叉交替去重合并
      candidatesForRerank = interleaveAndDeduplicate(bm25Candidates, knnCandidates);
    }

    // 6. 委托 RerankGateway 进行精排截取
    RerankCommand rerankCommand =
        RerankCommand.of(command.query(), candidatesForRerank, targetTopK);
    List<ScoredDocChunkVO> rerankedResults =
        rerankGateway != null ? rerankGateway.rerank(rerankCommand) : List.of();

    // 7. 终态去重与最低相似度阈值过滤保护
    Set<String> finalSeenIds = new HashSet<>();
    List<ScoredDocChunkVO> finalResults = new ArrayList<>();

    double minScoreThreshold =
        command.minScore() != null
            ? command.minScore()
            : (aiProperties != null && aiProperties.getRag() != null
                ? aiProperties.getRag().getSimilarityThreshold()
                : 0.0);

    for (ScoredDocChunkVO item : rerankedResults) {
      if (item.chunk() == null || item.chunk().getId() == null) {
        continue;
      }
      if (finalSeenIds.add(item.chunk().getId())) {
        // RRF 打分处于 0.01~0.03 倒数区间，不应用绝对 Cosine 阈值；非 RRF 策略则执行阈值过滤
        if (strategy == RerankStrategyEnum.RRF || item.score() >= minScoreThreshold) {
          finalResults.add(item);
        }
      }
    }

    long cost = System.currentTimeMillis() - startTime;
    log.info(
        "【HybridSearch】双路召回与重排完成: query=[{}], 精排输出数={}, 耗时={}ms, 策略={}",
        command.query(),
        finalResults.size(),
        cost,
        strategy);

    return List.copyOf(finalResults);
  }

  /** 执行 BM25 词法全文检索 */
  private List<DocChunkDocument> searchLexical(HybridSearchCommand command, int limit) {
    try {
      NativeQueryBuilder builder = NativeQuery.builder();
      builder.withQuery(
          q ->
              q.bool(
                  b -> {
                    // Must: 多字段 IK 分词全文加权匹配
                    b.must(
                        m ->
                            m.multiMatch(
                                mm ->
                                    mm.query(command.query())
                                        .fields(
                                            List.of("title^3", "content^2", "contextPrefix^1"))));

                    // Filter 1: 工作区隔离过滤器 (强安全隔离)
                    b.filter(
                        f ->
                            f.term(
                                t ->
                                    t.field("workspaceId")
                                        .value(String.valueOf(command.workspaceId()))));

                    // Filter 2: 可选文档范围过滤
                    if (command.documentId() != null) {
                      b.filter(
                          f ->
                              f.term(
                                  t ->
                                      t.field("documentId")
                                          .value(String.valueOf(command.documentId()))));
                    }

                    // Filter 3: 可选分类范围过滤
                    if (command.categoryId() != null) {
                      b.filter(
                          f ->
                              f.term(
                                  t ->
                                      t.field("categoryId")
                                          .value(String.valueOf(command.categoryId()))));
                    }

                    // Filter 4: 可选标签精确过滤
                    if (command.tags() != null && !command.tags().isEmpty()) {
                      List<FieldValue> tagValues =
                          command.tags().stream().map(FieldValue::of).toList();
                      b.filter(f -> f.terms(ts -> ts.field("tags").terms(t -> t.value(tagValues))));
                    }

                    return b;
                  }));

      builder.withPageable(PageRequest.of(0, limit));
      SearchHits<DocChunkDocument> hits =
          elasticsearchOperations.search(builder.build(), DocChunkDocument.class);

      if (hits == null || hits.isEmpty()) {
        return List.of();
      }
      return hits.getSearchHits().stream()
          .map(org.springframework.data.elasticsearch.core.SearchHit::getContent)
          .toList();
    } catch (Exception e) {
      log.warn(
          "【HybridSearch】BM25 词法全文检索异常: query=[{}], error={}", command.query(), e.getMessage());
      return List.of();
    }
  }

  /** 执行 kNN 向量相似度检索 */
  private List<DocChunkDocument> searchVector(
      HybridSearchCommand command, float[] queryVector, int limit) {
    try {
      List<Float> vectorList = new ArrayList<>(queryVector.length);
      for (float f : queryVector) {
        vectorList.add(f);
      }

      NativeQueryBuilder builder = NativeQuery.builder();
      builder.withKnnSearches(
          knn -> {
            knn.field("content_vector")
                .queryVector(vectorList)
                .k(limit)
                .numCandidates(Math.max(limit * 2, 50))
                .filter(
                    f ->
                        f.bool(
                            b -> {
                              // Filter 1: 工作区隔离过滤器
                              b.filter(
                                  bf ->
                                      bf.term(
                                          t ->
                                              t.field("workspaceId")
                                                  .value(String.valueOf(command.workspaceId()))));

                              // Filter 2: 可选文档范围过滤
                              if (command.documentId() != null) {
                                b.filter(
                                    bf ->
                                        bf.term(
                                            t ->
                                                t.field("documentId")
                                                    .value(String.valueOf(command.documentId()))));
                              }

                              // Filter 3: 可选分类范围过滤
                              if (command.categoryId() != null) {
                                b.filter(
                                    bf ->
                                        bf.term(
                                            t ->
                                                t.field("categoryId")
                                                    .value(String.valueOf(command.categoryId()))));
                              }

                              // Filter 4: 可选标签精确过滤
                              if (command.tags() != null && !command.tags().isEmpty()) {
                                List<FieldValue> tagValues =
                                    command.tags().stream().map(FieldValue::of).toList();
                                b.filter(
                                    bf ->
                                        bf.terms(
                                            ts -> ts.field("tags").terms(t -> t.value(tagValues))));
                              }

                              return b;
                            }));
            return knn;
          });

      builder.withPageable(PageRequest.of(0, limit));
      SearchHits<DocChunkDocument> hits =
          elasticsearchOperations.search(builder.build(), DocChunkDocument.class);

      if (hits == null || hits.isEmpty()) {
        return List.of();
      }
      return hits.getSearchHits().stream()
          .map(org.springframework.data.elasticsearch.core.SearchHit::getContent)
          .toList();
    } catch (Exception e) {
      log.warn("【HybridSearch】kNN 向量检索异常: query=[{}], error={}", command.query(), e.getMessage());
      return List.of();
    }
  }

  /** 对双路初筛结果进行交替轮询与去重合并 */
  private List<DocChunkDocument> interleaveAndDeduplicate(
      List<DocChunkDocument> list1, List<DocChunkDocument> list2) {
    List<DocChunkDocument> result = new ArrayList<>();
    Set<String> seenIds = new HashSet<>();
    int maxLen = Math.max(list1.size(), list2.size());

    for (int i = 0; i < maxLen; i++) {
      if (i < list1.size()) {
        DocChunkDocument doc1 = list1.get(i);
        if (doc1 != null && doc1.getId() != null && seenIds.add(doc1.getId())) {
          result.add(doc1);
        }
      }
      if (i < list2.size()) {
        DocChunkDocument doc2 = list2.get(i);
        if (doc2 != null && doc2.getId() != null && seenIds.add(doc2.getId())) {
          result.add(doc2);
        }
      }
    }
    return result;
  }
}
