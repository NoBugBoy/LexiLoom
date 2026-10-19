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

    // 5. 候选集组装与重排路由
    RerankStrategyEnum strategy = rerankGateway != null ? rerankGateway.getStrategy() : null;
    double minScoreThreshold = resolveMinScoreThreshold(command);
    int maxChunksPerDocument = resolveMaxChunksPerDocument();

    List<ScoredDocChunkVO> finalResults;
    if (strategy == RerankStrategyEnum.RRF) {
      // RRF 算法专门需要多路排名数据进行倒数排名融合累加，保留双路原序列
      List<DocChunkDocument> candidatesForRerank =
          new ArrayList<>(bm25Candidates.size() + knnCandidates.size());
      candidatesForRerank.addAll(bm25Candidates);
      candidatesForRerank.addAll(knnCandidates);

      RerankCommand rerankCommand =
          RerankCommand.of(
              command.query(),
              candidatesForRerank,
              Math.max(targetTopK, candidatesForRerank.size()));
      List<ScoredDocChunkVO> rerankedResults =
          rerankGateway != null ? rerankGateway.rerank(rerankCommand) : List.of();

      finalResults =
          selectFinalResults(
              rerankedResults,
              command.query(),
              vector,
              strategy,
              minScoreThreshold,
              maxChunksPerDocument,
              targetTopK);
    } else if (knnCandidates.isEmpty()) {
      // 纯词法回退场景 (如未提取到向量或向量索引无命中)：全部配额由 BM25 承担
      RerankCommand rerankCommand =
          RerankCommand.of(
              command.query(), bm25Candidates, Math.max(targetTopK, bm25Candidates.size()));
      List<ScoredDocChunkVO> rerankedResults =
          rerankGateway != null ? rerankGateway.rerank(rerankCommand) : List.of();
      finalResults =
          selectFinalResults(
                  rerankedResults,
                  command.query(),
                  vector,
                  strategy,
                  minScoreThreshold,
                  maxChunksPerDocument,
                  targetTopK)
              .stream()
              .map(vo -> ScoredDocChunkVO.of(vo.chunk(), vo.score(), vo.originalRank(), "BM25"))
              .toList();
    } else {
      // 配额隔离混合策略：仅将向量检索结果交由 Rerank 精排截取 Top-15，额外保留 5 条 BM25 词法结果，共 20 条交给 LLM
      finalResults =
          executeQuotaHybridSearch(
              command,
              vector,
              bm25Candidates,
              knnCandidates,
              strategy,
              minScoreThreshold,
              maxChunksPerDocument,
              targetTopK);
    }

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
      double baseScore;
      if (strategy == RerankStrategyEnum.CROSS_ENCODER && item.score() > 0.0) {
        baseScore = item.score();
      } else {
        baseScore = (cosine != null) ? cosine : item.score();
      }

      // 阈值过滤机制：基于真实语义相似度进行严格过滤与结构保护 (表结构宽松阈值：table 切片或包含数据表结构定义时，宽松 0.65 保护)
      boolean isTable = isTableIntent && isTableChunk(chunk);
      double effectiveThreshold = isTable ? minScoreThreshold * 0.65 : minScoreThreshold;
      if (strategy != RerankStrategyEnum.RRF
          && (strategy == RerankStrategyEnum.CROSS_ENCODER || cosine != null)) {
        if (baseScore < effectiveThreshold) {
          continue;
        }
      }

      // 综合排序分数计算：
      // 1. 基础分取真实余弦相似度 (或重排打分)；
      // 2. 结合初筛原始排位平滑增益 (RRF 倒数排位)，尊重双路检索初筛结果；
      // 3. 意图感知加权：当用户查询显式提及"表"或"table"且切片类型为结构化表格时，赋予类型对齐增益。
      double rankBonus = 1.0 / (60.0 + item.originalRank());
      double intentBonus = isTable ? 0.05 : 0.0;
      double sortScore = baseScore + rankBonus + intentBonus;

      candidateItems.add(
          new CandidateItem(
              chunk,
              baseScore,
              sortScore,
              item.originalRank(),
              StrUtil.blankToDefault(item.recallSource(), "RERANK")));
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
        selected.add(
            ScoredDocChunkVO.of(
                item.chunk(), item.displayScore(), item.originalRank(), item.recallSource()));
        continue;
      }
      int used = perDocumentCount.getOrDefault(documentId, 0);
      if (used < maxChunksPerDocument) {
        perDocumentCount.put(documentId, used + 1);
        selected.add(
            ScoredDocChunkVO.of(
                item.chunk(), item.displayScore(), item.originalRank(), item.recallSource()));
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
        selected.add(
            ScoredDocChunkVO.of(
                item.chunk(), item.displayScore(), item.originalRank(), item.recallSource()));
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

  private boolean isTableChunk(DocChunkDocument chunk) {
    if (chunk == null) {
      return false;
    }
    if ("table".equalsIgnoreCase(chunk.getChunkType())) {
      return true;
    }
    String content = chunk.getContent();
    if (StrUtil.isNotBlank(content)) {
      return content.contains("【数据表结构定义】")
          || (content.contains("|")
              && (content.contains("字段") || content.contains("类型") || content.contains("注释")));
    }
    return false;
  }

  /**
   * 对词法全文检索关键词进行自然语言提问降噪与核心实体提取
   *
   * <p>Dense 向量检索需保留完整自然语言语义，而 BM25 作为词袋 TF-IDF 算法，极易被自然语言中的提问疑问词、
   * 指令助词（如“有哪些”、“要返回”、“请问”）严重稀释和污染，导致命中大量包含“返回值”、“有哪些”的高频无关接口文档。 此处剥离自然语言提问助词与指令词，提取纯净的实体与技术核心词交付
   * BM25 检索。
   */
  private String cleanLexicalQuery(String rawQuery) {
    if (StrUtil.isBlank(rawQuery)) {
      return "";
    }
    // 1. 过滤自然语言提问与格式指令虚词（如“有哪些”、“要返回”、“请问”、“列出”）
    String cleaned =
        rawQuery.replaceAll("(?i)(有哪些|有什么|是什么|怎么做|如何|为什么|请问|请告诉我|告诉我|请给出|请列出|要返回|需要返回|详细说明)", " ");
    // 2. 过滤常见无检索区分度的连词、助词
    cleaned =
        cleaned.replaceAll(
            "(?i)(?:^|\\s+|(?<=[\u4e00-\u9fa5a-zA-Z0-9]))(和|及|与|或|的|了|吗|呢|吧|啊|呀)(?:$|\\s+|(?=[\u4e00-\u9fa5a-zA-Z0-9]))",
            " ");
    // 3. 规范化空白字符
    cleaned = cleaned.replaceAll("\\s+", " ").trim();
    return StrUtil.isNotBlank(cleaned) ? cleaned : rawQuery.trim();
  }

  private record CandidateItem(
      DocChunkDocument chunk,
      double displayScore,
      double sortScore,
      int originalRank,
      String recallSource) {}

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
      String lexicalQuery = cleanLexicalQuery(command.query());
      NativeQueryBuilder builder = NativeQuery.builder();
      builder.withQuery(
          q ->
              q.bool(
                  b -> {
                    // Must: 多字段 IK 分词加权匹配。
                    // 采用 best_fields + tie_breaker 组合：切片正文权重最高，标题/章节路径仅作辅助加权。
                    // 词法检索采用 cleanLexicalQuery 剥离自然语言提问与格式指令虚词（如“有哪些”、“要返回”），
                    // 确保聚焦技术核心实体词（如 S16、数据库表、表名、表注释），杜绝无关接口返回文档刷屏。
                    b.must(
                        m ->
                            m.multiMatch(
                                mm ->
                                    mm.query(lexicalQuery)
                                        .type(TextQueryType.BestFields)
                                        .tieBreaker(0.5)
                                        .fields(
                                            List.of(
                                                "content^3",
                                                "contextPrefix^2.5",
                                                "title^1",
                                                "headerPath^1.5"))));

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

                    // Filter 5: 文档可见性与租户细粒度 ACL 过滤
                    applyAclFilter(b, command);

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

                              // Filter 5: 文档可见性与租户细粒度 ACL 过滤
                              applyAclFilter(b, command);

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

  /**
   * 配额隔离混合检索执行：向量精排 Top-N + BM25 词法保底 Top-M
   *
   * @param command 原始检索命令
   * @param vector 查询向量
   * @param bm25Candidates 词法检索候选集
   * @param knnCandidates 稠密向量检索候选集
   * @param strategy 重排算法策略
   * @param minScoreThreshold 最低相似度阈值
   * @param maxChunksPerDocument 单文档切片上限
   * @param targetTopK 最终目标召回总数
   * @return 最终融合切片列表
   */
  private List<ScoredDocChunkVO> executeQuotaHybridSearch(
      HybridSearchCommand command,
      float[] vector,
      List<DocChunkDocument> bm25Candidates,
      List<DocChunkDocument> knnCandidates,
      RerankStrategyEnum strategy,
      double minScoreThreshold,
      int maxChunksPerDocument,
      int targetTopK) {

    int vectorQuota = resolveVectorTopK(targetTopK);
    int lexicalQuota = resolveLexicalTopK(targetTopK, vectorQuota);

    // 1. 向量路交由 Rerank Gateway 精排处理并截取 vectorQuota 条
    List<ScoredDocChunkVO> vectorSelected = new ArrayList<>();
    if (!knnCandidates.isEmpty()) {
      RerankCommand rerankCommand =
          RerankCommand.of(
              command.query(), knnCandidates, Math.max(vectorQuota, knnCandidates.size()));
      List<ScoredDocChunkVO> rerankedKnn =
          rerankGateway != null ? rerankGateway.rerank(rerankCommand) : List.of();

      vectorSelected =
          new ArrayList<>(
              selectFinalResults(
                  rerankedKnn,
                  command.query(),
                  vector,
                  strategy,
                  minScoreThreshold,
                  maxChunksPerDocument,
                  vectorQuota));
    }

    // 2. 收集已选入向量路的切片 ID 与文档计数，用于 BM25 词法保底排重与多样性协同
    Set<String> selectedChunkIds = new HashSet<>();
    Map<String, Integer> perDocumentCount = new HashMap<>();
    for (ScoredDocChunkVO vo : vectorSelected) {
      if (vo != null && vo.chunk() != null && vo.chunk().getId() != null) {
        selectedChunkIds.add(vo.chunk().getId());
        String docId = vo.chunk().getDocumentId();
        if (StrUtil.isNotBlank(docId)) {
          perDocumentCount.put(docId, perDocumentCount.getOrDefault(docId, 0) + 1);
        }
      }
    }

    // 3. 词法路 (BM25) 独立保底配额提取 (排除已入围切片，确保精确关键词命中切片保底入围)
    int remainingQuota = Math.max(0, targetTopK - vectorSelected.size());
    int targetLexicalCount = Math.min(lexicalQuota, remainingQuota);
    // 若向量路入围不足（如向量无匹配或因阈值过滤淘汰），允许词法路弹性补齐差额
    if (vectorSelected.size() < vectorQuota) {
      targetLexicalCount = remainingQuota;
    }

    List<ScoredDocChunkVO> lexicalSelected = new ArrayList<>();
    List<ScoredDocChunkVO> lexicalBackfill = new ArrayList<>();
    int lexicalRankCounter = vectorSelected.size() + 1;

    for (int i = 0; i < bm25Candidates.size(); i++) {
      if (lexicalSelected.size() >= targetLexicalCount) {
        break;
      }
      DocChunkDocument doc = bm25Candidates.get(i);
      if (doc == null || doc.getId() == null || selectedChunkIds.contains(doc.getId())) {
        continue;
      }

      Double cosine = cosineSimilarity(vector, doc.getContentVector());
      // 词法保底打分：优先取真实向量余弦；无向量时取词法排位平滑衰减
      double score = (cosine != null && cosine > 0.0) ? cosine : (1.0 / (60.0 + (i + 1)));

      String documentId = doc.getDocumentId();
      ScoredDocChunkVO vo = ScoredDocChunkVO.of(doc, score, lexicalRankCounter++, "BM25");
      if (maxChunksPerDocument <= 0 || StrUtil.isBlank(documentId)) {
        lexicalSelected.add(vo);
        selectedChunkIds.add(doc.getId());
      } else {
        int currentCount = perDocumentCount.getOrDefault(documentId, 0);
        if (currentCount < maxChunksPerDocument) {
          perDocumentCount.put(documentId, currentCount + 1);
          lexicalSelected.add(vo);
          selectedChunkIds.add(doc.getId());
        } else {
          lexicalBackfill.add(vo);
        }
      }
    }

    // 若多样性初选后仍未达到目标数量，从词法备选池补齐
    if (lexicalSelected.size() < targetLexicalCount && !lexicalBackfill.isEmpty()) {
      for (ScoredDocChunkVO backfillItem : lexicalBackfill) {
        if (lexicalSelected.size() >= targetLexicalCount) {
          break;
        }
        if (selectedChunkIds.add(backfillItem.chunk().getId())) {
          lexicalSelected.add(backfillItem);
        }
      }
    }

    // 4. 合并最终结果：向量精排 Top-N + 词法保底 Top-M
    List<ScoredDocChunkVO> finalResults =
        new ArrayList<>(vectorSelected.size() + lexicalSelected.size());
    finalResults.addAll(vectorSelected);
    finalResults.addAll(lexicalSelected);

    log.info(
        "【HybridSearch】配额隔离混合召回完成: 目标TopK={}, 向量精排入围={}, 词法保底入围={}, 总输出={}",
        targetTopK,
        vectorSelected.size(),
        lexicalSelected.size(),
        finalResults.size());

    return finalResults;
  }

  /**
   * 文档可见性与租户细粒度 ACL 过滤 (非管理员用户限制访问私密文档)
   *
   * <p>规则：
   * <ul>
   *   <li>若当前用户为空间管理员 (command.isAdmin() == true)，跳过 ACL 过滤，允许查阅空间内全部切片；</li>
   *   <li>若为普通成员/访客，限制切片满足下列任一条件 (should + minimumShouldMatch=1)：
   *     <ol>
   *       <li>visibility 为 PUBLIC 或 INTERNAL；</li>
   *       <li>visibility 为 PRIVATE 且 createBy 等于当前登录用户 ID；</li>
   *       <li>历史切片向后兼容：切片缺少 visibility 字段 (must_not exists)，视为空间成员默认可读。</li>
   *     </ol>
   *   </li>
   * </ul>
   */
  private void applyAclFilter(
      co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder b,
      HybridSearchCommand command) {
    if (command.isAdmin()) {
      return;
    }

    b.filter(
        af ->
            af.bool(
                ab -> {
                  // 1. PUBLIC 或 INTERNAL 可见
                  ab.should(
                      s ->
                          s.terms(
                              ts ->
                                  ts.field("visibility")
                                      .terms(
                                          t ->
                                              t.value(
                                                  List.of(
                                                      FieldValue.of("PUBLIC"),
                                                      FieldValue.of("INTERNAL"))))));

                  // 2. 自己创建的 PRIVATE 文档
                  if (command.userId() != null) {
                    ab.should(
                        s ->
                            s.bool(
                                pb ->
                                    pb.filter(
                                            pf ->
                                                pf.term(
                                                    t ->
                                                        t.field("visibility")
                                                            .value("PRIVATE")))
                                        .filter(
                                            pf ->
                                                pf.term(
                                                    t ->
                                                        t.field("createBy")
                                                            .value(String.valueOf(command.userId()))))));
                  }

                  // 3. 兼容历史数据：若切片未落库 visibility 字段，则默认允许工作区成员访问
                  ab.should(
                      s ->
                          s.bool(
                              hb ->
                                  hb.mustNot(
                                      mn ->
                                          mn.exists(
                                              e -> e.field("visibility")))));

                  ab.minimumShouldMatch("1");
                  return ab;
                }));
  }

  private int resolveVectorTopK(int totalTopK) {
    if (aiProperties != null && aiProperties.getRag() != null) {
      int cfgVector = aiProperties.getRag().getVectorTopK();
      int cfgTotal = aiProperties.getRag().getTopK();
      if (totalTopK == cfgTotal) {
        return cfgVector;
      }
      double ratio = cfgTotal > 0 ? (double) cfgVector / cfgTotal : 0.75;
      return Math.max(1, (int) Math.round(totalTopK * ratio));
    }
    return Math.max(1, (int) Math.round(totalTopK * 0.75));
  }

  private int resolveLexicalTopK(int totalTopK, int vectorQuota) {
    return Math.max(0, totalTopK - vectorQuota);
  }
}
