package io.lexiloom.application.ai.search;

import cn.hutool.core.util.StrUtil;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.enums.RerankStrategyEnum;
import io.lexiloom.application.ai.gateway.embedding.EmbeddingGateway;
import io.lexiloom.application.ai.gateway.rerank.RerankCommand;
import io.lexiloom.application.ai.gateway.rerank.RerankGateway;
import io.lexiloom.application.ai.gateway.rerank.ScoredDocChunkVO;
import io.lexiloom.application.ai.model.search.DocChunkDocument;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 *         <li>路径 1 (词法全文检索)：基于 IK 分词的 multi_match (content^3, headerPath^1.5, title^1,
 *             contextPrefix^0.5，best_fields + tie_breaker)，正文主导打分、文档级冗余字段降权以防同文档并列刷屏；
 *         <li>路径 2 (稠密向量检索)：基于 Elasticsearch 8.x kNN 检索 content_vector 向量相似度；
 *       </ul>
 *   <li>多租户过滤：双路检索强制附加 {@code workspaceId} 过滤，杜绝跨租户数据越权；
 *   <li>精排融合：委托 {@link RerankGateway}（NoOp / RRF / CrossEncoder）执行打分排序；
 *   <li>终态选择：真实向量余弦回填 + 相似度阈值过滤 + 文档级多样性上限 + Top-K 截取。
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
    // 候选池需显著大于目标条数，才能为文档级多样性上限与真实相似度阈值留出足够筛选余量
    int candidateLimit = Math.max(targetTopK * 5, 50);

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

    // 6. 委托 RerankGateway 进行精排打分 (仅负责打分与排序，最终截取统一交由服务层处理，
    //    以便在截取前完成真实相似度回填、文档级多样性上限与阈值过滤)
    RerankCommand rerankCommand =
        RerankCommand.of(
            command.query(), candidatesForRerank, Math.max(targetTopK, candidatesForRerank.size()));
    List<ScoredDocChunkVO> rerankedResults =
        rerankGateway != null ? rerankGateway.rerank(rerankCommand) : List.of();

    // 7. 终态选择：真实 Cosine 回填 -> 阈值过滤 -> 文档级多样性上限 -> Top-K 截取
    double minScoreThreshold = resolveMinScoreThreshold(command);
    int maxChunksPerDocument = resolveMaxChunksPerDocument();
    List<ScoredDocChunkVO> finalResults =
        selectFinalResults(
            rerankedResults,
            command.query(),
            vector,
            strategy,
            minScoreThreshold,
            maxChunksPerDocument,
            targetTopK);

    long cost = System.currentTimeMillis() - startTime;
    log.info(
        "【HybridSearch】双路召回与重排完成: query=[{}], 精排输出数={}, 阈值={}, 单文档切片上限={}, 耗时={}ms, 策略={}",
        command.query(),
        finalResults.size(),
        minScoreThreshold,
        maxChunksPerDocument,
        cost,
        strategy);

    return List.copyOf(finalResults);
  }

  /** 解析最低相似度阈值：优先取调用方入参，其次取全局 RAG 配置 */
  private double resolveMinScoreThreshold(HybridSearchCommand command) {
    if (command.minScore() != null) {
      return command.minScore();
    }
    return (aiProperties != null && aiProperties.getRag() != null)
        ? aiProperties.getRag().getSimilarityThreshold()
        : 0.0;
  }

  /** 解析单文档切片数量上限：{@code <= 0} 表示关闭文档级多样性保护 */
  private int resolveMaxChunksPerDocument() {
    return (aiProperties != null && aiProperties.getRag() != null)
        ? aiProperties.getRag().getMaxChunksPerDocument()
        : 0;
  }

  /**
   * 终态结果选择：真实余弦回填 + 相似度阈值过滤 + 文档级多样性上限 + Top-K 截取
   *
   * <p>核心动机：同一文档内关键词高频重复时，双路召回会被同文档的近似切片刷屏，导致其他文档甚至真正需要的切片被挤出结果。 此处以真实向量余弦相似度重新打分，并按 {@code
   * maxChunksPerDocument} 限制单文档占比。
   */
  private List<ScoredDocChunkVO> selectFinalResults(
      List<ScoredDocChunkVO> rerankedResults,
      String query,
      float[] queryVector,
      RerankStrategyEnum strategy,
      double minScoreThreshold,
      int maxChunksPerDocument,
      int targetTopK) {

    Set<String> seenChunkIds = new HashSet<>();
    List<CandidateItem> candidateItems = new ArrayList<>(rerankedResults.size());
    boolean isTableIntent = isTableQuery(query);

    for (ScoredDocChunkVO item : rerankedResults) {
      if (item == null || item.chunk() == null || item.chunk().getId() == null) {
        continue;
      }
      if (!seenChunkIds.add(item.chunk().getId())) {
        continue;
      }

      DocChunkDocument chunk = item.chunk();
      Double cosine = cosineSimilarity(queryVector, chunk.getContentVector());
      double baseScore = (cosine != null) ? cosine : item.score();

      // 阈值过滤机制：基于真实语义相似度进行严格过滤与结构保护
      if (strategy != RerankStrategyEnum.RRF && cosine != null) {
        boolean isHighLexicalMatch =
            item.originalRank() <= 5 || "table".equalsIgnoreCase(chunk.getChunkType());
        double effectiveThreshold =
            isHighLexicalMatch ? Math.min(minScoreThreshold * 0.65, 0.22) : minScoreThreshold;
        if (baseScore < effectiveThreshold) {
          continue;
        }
      }

      // 综合排序分数计算：
      // 1. 基础分取真实余弦相似度 (或重排打分)；
      // 2. 结合初筛原始排位平滑增益 (RRF 倒数排位)，尊重双路检索初筛结果；
      // 3. 意图感知加权：当用户查询显式提及"表"或"table"且切片类型为结构化表格时，赋予类型对齐增益。
      double rankBonus = 1.0 / (60.0 + item.originalRank());
      double typeBoost =
          (isTableIntent && "table".equalsIgnoreCase(chunk.getChunkType())) ? 0.15 : 0.0;
      double sortScore = baseScore + rankBonus + typeBoost;

      candidateItems.add(new CandidateItem(chunk, baseScore, sortScore, item.originalRank()));
    }

    // 按综合排序得分降序排列（分数相同时保持原始顺序）
    candidateItems.sort(Comparator.comparingDouble(CandidateItem::sortScore).reversed());

    List<ScoredDocChunkVO> selected = new ArrayList<>(Math.min(targetTopK, candidateItems.size()));
    List<CandidateItem> backfillPool = new ArrayList<>();
    Map<String, Integer> perDocumentCount = new HashMap<>();
    int diversityTruncated = 0;

    // 第一阶段：多样性优先（按 maxChunksPerDocument 限制单文档初选量，保证多文档均衡展示）
    for (CandidateItem item : candidateItems) {
      if (selected.size() >= targetTopK) {
        break;
      }
      String documentId = item.chunk().getDocumentId();
      if (maxChunksPerDocument <= 0 || StrUtil.isBlank(documentId)) {
        selected.add(ScoredDocChunkVO.of(item.chunk(), item.displayScore(), item.originalRank()));
        continue;
      }
      int used = perDocumentCount.getOrDefault(documentId, 0);
      if (used < maxChunksPerDocument) {
        perDocumentCount.put(documentId, used + 1);
        selected.add(ScoredDocChunkVO.of(item.chunk(), item.displayScore(), item.originalRank()));
      } else {
        backfillPool.add(item);
        diversityTruncated++;
      }
    }

    // 第二阶段：目标 Top-K 兜底补齐（Backfill）
    // 当多文档未填满 targetTopK（如集中在单文档或仅有少数文档匹配）时，从回填池中按相似度降序依次补齐，
    // 杜绝因多样性硬截断导致召回数量骤降、关键设计全貌丢失的问题。
    if (selected.size() < targetTopK && !backfillPool.isEmpty()) {
      int beforeCount = selected.size();
      for (CandidateItem item : backfillPool) {
        if (selected.size() >= targetTopK) {
          break;
        }
        selected.add(ScoredDocChunkVO.of(item.chunk(), item.displayScore(), item.originalRank()));
      }
      log.info(
          "【HybridSearch】多样性回填生效: 首轮仅选出 {} 条，从备选池按分数补齐 {} 条同文档切片，最终输出 {} 条",
          beforeCount,
          selected.size() - beforeCount,
          selected.size());
    } else if (diversityTruncated > 0) {
      log.info("【HybridSearch】文档级多样性上限生效: 单文档最多 {} 条，多余候选已排除", maxChunksPerDocument);
    }

    return List.copyOf(selected);
  }

  private boolean isTableQuery(String query) {
    if (StrUtil.isBlank(query)) {
      return false;
    }
    String lower = query.toLowerCase();
    return lower.contains("表") || lower.contains("table");
  }

  private record CandidateItem(
      DocChunkDocument chunk, double displayScore, double sortScore, int originalRank) {}

  /**
   * 计算查询向量与切片向量的真实余弦相似度
   *
   * @return 相似度取值 [-1, 1]；任一侧向量缺失、为零向量或维度不一致时返回 {@code null}（表示无法给出真实语义分数）
   */
  private Double cosineSimilarity(float[] queryVector, float[] chunkVector) {
    if (queryVector == null
        || queryVector.length == 0
        || chunkVector == null
        || chunkVector.length == 0) {
      return null;
    }
    if (queryVector.length != chunkVector.length) {
      log.warn(
          "【HybridSearch】查询向量与切片向量维度不一致，跳过真实相似度计算: queryDim={}, chunkDim={}",
          queryVector.length,
          chunkVector.length);
      return null;
    }

    double dot = 0.0;
    double queryNorm = 0.0;
    double chunkNorm = 0.0;
    for (int i = 0; i < queryVector.length; i++) {
      dot += (double) queryVector[i] * chunkVector[i];
      queryNorm += (double) queryVector[i] * queryVector[i];
      chunkNorm += (double) chunkVector[i] * chunkVector[i];
    }
    if (queryNorm <= 0.0 || chunkNorm <= 0.0) {
      return null;
    }
    return dot / (Math.sqrt(queryNorm) * Math.sqrt(chunkNorm));
  }

  /** 执行 BM25 词法全文检索 */
  private List<DocChunkDocument> searchLexical(HybridSearchCommand command, int limit) {
    try {
      NativeQueryBuilder builder = NativeQuery.builder();
      builder.withQuery(
          q ->
              q.bool(
                  b -> {
                    // Must: 多字段 IK 分词加权匹配。
                    //
                    // 采用 best_fields + tie_breaker 组合：切片正文权重最高，标题/章节路径仅作辅助加权。
                    // 关键动机：title 与 contextPrefix 是文档级冗余字段，同一文档内每个切片取值完全一致，
                    // 若给它们高权重，关键词命中标题时该文档全部切片会 BM25 同分并列，再按内部顺序刷屏召回结果。
                    // tie_breaker 让非最优字段的得分参与累加，从而由各切片独有正文打破并列。
                    b.must(
                        m ->
                            m.multiMatch(
                                mm ->
                                    mm.query(command.query())
                                        .type(TextQueryType.BestFields)
                                        .tieBreaker(0.5)
                                        .fields(
                                            List.of(
                                                "content^3",
                                                "contextPrefix^2.5",
                                                "title^1",
                                                "headerPath^1.5"))));

                    // Should: 若用户提问包含表格意图，对表格类型切片赋予轻量加权
                    String qLower = command.query().toLowerCase();
                    if (qLower.contains("表") || qLower.contains("table")) {
                      b.should(s -> s.term(t -> t.field("chunkType").value("table").boost(1.5f)));
                    }

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
