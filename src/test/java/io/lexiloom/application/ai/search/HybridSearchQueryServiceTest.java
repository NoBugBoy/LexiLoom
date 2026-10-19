package io.lexiloom.application.ai.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.enums.RerankStrategyEnum;
import io.lexiloom.application.ai.gateway.embedding.EmbeddingGateway;
import io.lexiloom.application.ai.gateway.rerank.RerankCommand;
import io.lexiloom.application.ai.gateway.rerank.RerankGateway;
import io.lexiloom.application.ai.gateway.rerank.ScoredDocChunkVO;
import io.lexiloom.application.ai.model.search.DocChunkDocument;
import io.lexiloom.application.exception.BusinessException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;

@ExtendWith(MockitoExtension.class)
class HybridSearchQueryServiceTest {

  @Mock private ElasticsearchOperations elasticsearchOperations;
  @Mock private EmbeddingGateway embeddingGateway;
  @Mock private RerankGateway rerankGateway;

  private AiProperties aiProperties;
  private DefaultHybridSearchQueryService service;

  @BeforeEach
  void setUp() {
    aiProperties = new AiProperties();
    service =
        new DefaultHybridSearchQueryService(
            elasticsearchOperations, embeddingGateway, rerankGateway, aiProperties);
  }

  @SuppressWarnings("unchecked")
  private SearchHits<DocChunkDocument> createSearchHits(List<DocChunkDocument> docs) {
    SearchHits<DocChunkDocument> hits = mock(SearchHits.class);
    List<SearchHit<DocChunkDocument>> hitList = new ArrayList<>();
    for (DocChunkDocument doc : docs) {
      SearchHit<DocChunkDocument> hit = mock(SearchHit.class);
      when(hit.getContent()).thenReturn(doc);
      hitList.add(hit);
    }
    org.mockito.Mockito.lenient().when(hits.isEmpty()).thenReturn(docs.isEmpty());
    org.mockito.Mockito.lenient().when(hits.getSearchHits()).thenReturn(hitList);
    return hits;
  }

  @Test
  @DisplayName("参数校验：workspaceId 为空或关键词为空时抛出业务异常")
  void testParameterValidation() {
    assertThatThrownBy(
            () -> new HybridSearchCommand(null, "query", null, 5, null, null, null, null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("工作区ID不能为空");

    assertThatThrownBy(() -> new HybridSearchCommand(1L, "  ", null, 5, null, null, null, null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("检索关键词不能为空");
  }

  @Test
  @DisplayName("双路召回成功：未传 queryVector 时自动调用 EmbeddingGateway 嵌入，并强制带 workspaceId 过滤")
  void testDualPathHybridSearch_Success() {
    Long wsId = 100L;
    String query = "分布式存储";
    float[] mockVector = new float[] {0.1f, 0.2f, 0.3f};
    when(embeddingGateway.embed(query)).thenReturn(mockVector);

    DocChunkDocument bm25Doc =
        DocChunkDocument.builder()
            .id("1_0")
            .workspaceId("100")
            .documentId("1")
            .title("BM25命中")
            .build();
    DocChunkDocument knnDoc =
        DocChunkDocument.builder()
            .id("2_0")
            .workspaceId("100")
            .documentId("2")
            .title("kNN命中")
            .build();

    SearchHits<DocChunkDocument> bm25Hits = createSearchHits(List.of(bm25Doc));
    SearchHits<DocChunkDocument> knnHits = createSearchHits(List.of(knnDoc));

    when(elasticsearchOperations.search(any(NativeQuery.class), eq(DocChunkDocument.class)))
        .thenReturn(bm25Hits, knnHits);

    when(rerankGateway.getStrategy()).thenReturn(RerankStrategyEnum.NONE);
    when(rerankGateway.rerank(any(RerankCommand.class)))
        .thenAnswer(
            invocation -> {
              RerankCommand cmd = invocation.getArgument(0);
              return cmd.candidates().stream().map(c -> ScoredDocChunkVO.of(c, 0.9, 1)).toList();
            });

    HybridSearchCommand command = HybridSearchCommand.of(wsId, query, 5);
    List<ScoredDocChunkVO> results = service.hybridSearch(command);

    assertThat(results).hasSize(2);
    assertThat(results.get(0).chunk().getId()).isEqualTo("2_0");
    assertThat(results.get(1).chunk().getId()).isEqualTo("1_0");

    verify(embeddingGateway).embed(query);

    // 校验两次 ES 查询均传递了 NativeQuery
    ArgumentCaptor<NativeQuery> queryCaptor = ArgumentCaptor.forClass(NativeQuery.class);
    verify(elasticsearchOperations, org.mockito.Mockito.times(2))
        .search(queryCaptor.capture(), eq(DocChunkDocument.class));

    List<NativeQuery> queries = queryCaptor.getAllValues();
    assertThat(queries).hasSize(2);

    // 路径 1：词法检索采用 best_fields + tie_breaker，正文权重最高、文档级冗余字段降权，
    // 防止关键词命中文档标题时同文档全部切片 BM25 并列刷屏
    var multiMatch = queries.get(0).getQuery().bool().must().getFirst().multiMatch();
    assertThat(multiMatch).isNotNull();
    assertThat(multiMatch.tieBreaker()).isEqualTo(0.5);
    assertThat(multiMatch.minimumShouldMatch()).isNull();
    assertThat(multiMatch.fields())
        .containsExactly("content^3", "contextPrefix^2.5", "title^1", "headerPath^1.5");

    // 路径 2：向量检索使用 kNN 命中 content_vector
    assertThat(queries.get(1).getKnnSearches()).hasSize(1);
    assertThat(queries.get(1).getKnnSearches().getFirst().field()).isEqualTo("content_vector");
  }

  @Test
  @DisplayName("预置向量复用：当入参包含预计算 queryVector 时不再额外调用 EmbeddingGateway")
  void testPrecalculatedVectorReused() {
    Long wsId = 100L;
    float[] existingVector = new float[] {0.5f, 0.6f};
    HybridSearchCommand command = HybridSearchCommand.of(wsId, "查询", existingVector, 3);

    DocChunkDocument doc =
        DocChunkDocument.builder().id("3_0").workspaceId("100").documentId("3").title("命中").build();

    SearchHits<DocChunkDocument> hits1 = createSearchHits(List.of(doc));
    SearchHits<DocChunkDocument> hits2 = createSearchHits(List.of(doc));

    when(elasticsearchOperations.search(any(NativeQuery.class), eq(DocChunkDocument.class)))
        .thenReturn(hits1, hits2);

    when(rerankGateway.rerank(any(RerankCommand.class)))
        .thenReturn(List.of(ScoredDocChunkVO.of(doc, 0.95, 1)));

    List<ScoredDocChunkVO> results = service.hybridSearch(command);

    assertThat(results).hasSize(1);
    verify(embeddingGateway, never()).embed(any());
  }

  @Test
  @DisplayName("RRF 策略流转：当使用 RRF 时，向重排网关保留双路初筛序列以供倒数排名融合累加")
  void testRrfCandidatesPreserved() {
    Long wsId = 100L;
    HybridSearchCommand command = HybridSearchCommand.of(wsId, "查询", new float[] {0.1f}, 5);

    DocChunkDocument doc1 = DocChunkDocument.builder().id("1_0").title("Doc1").build();
    DocChunkDocument doc2 = DocChunkDocument.builder().id("2_0").title("Doc2").build();

    SearchHits<DocChunkDocument> bm25Hits = createSearchHits(List.of(doc1, doc2));
    SearchHits<DocChunkDocument> knnHits = createSearchHits(List.of(doc2));

    when(elasticsearchOperations.search(any(NativeQuery.class), eq(DocChunkDocument.class)))
        .thenReturn(bm25Hits, knnHits);

    when(rerankGateway.getStrategy()).thenReturn(RerankStrategyEnum.RRF);

    ArgumentCaptor<RerankCommand> captor = ArgumentCaptor.forClass(RerankCommand.class);
    when(rerankGateway.rerank(captor.capture()))
        .thenReturn(
            List.of(ScoredDocChunkVO.of(doc2, 0.032, 1), ScoredDocChunkVO.of(doc1, 0.016, 2)));

    List<ScoredDocChunkVO> results = service.hybridSearch(command);

    assertThat(results).hasSize(2);
    // 验证传递给 RerankCommand 的 candidates 包含了 3 个元素（包含重复出现的 doc2）
    RerankCommand passedCmd = captor.getValue();
    assertThat(passedCmd.candidates()).hasSize(3);
  }

  @Test
  @DisplayName("容错降级：向量检索发生异常时优雅回退，仍能基于纯词法全文命中返回结果")
  void testGracefulFallbackWhenVectorFails() {
    Long wsId = 100L;
    HybridSearchCommand command = HybridSearchCommand.of(wsId, "关键字", 5);

    when(embeddingGateway.embed("关键字")).thenThrow(new RuntimeException("Embedding 超时"));

    DocChunkDocument bm25Doc =
        DocChunkDocument.builder()
            .id("1_0")
            .workspaceId("100")
            .documentId("1")
            .title("词法命中")
            .build();

    SearchHits<DocChunkDocument> bm25Hits = createSearchHits(List.of(bm25Doc));

    when(elasticsearchOperations.search(any(NativeQuery.class), eq(DocChunkDocument.class)))
        .thenReturn(bm25Hits);

    when(rerankGateway.rerank(any(RerankCommand.class)))
        .thenReturn(List.of(ScoredDocChunkVO.of(bm25Doc, 0.85, 1)));

    List<ScoredDocChunkVO> results = service.hybridSearch(command);

    assertThat(results).hasSize(1);
    assertThat(results.getFirst().chunk().getId()).isEqualTo("1_0");
  }

  @Test
  @DisplayName("空命中保护：双路检索均无结果时直接返回空列表，不触发重排调用")
  void testEmptyHitsReturnsEmpty() {
    Long wsId = 100L;
    HybridSearchCommand command = HybridSearchCommand.of(wsId, "无任何匹配文本", 5);

    when(embeddingGateway.embed(any())).thenReturn(new float[] {0.1f});

    SearchHits<DocChunkDocument> emptyHits1 = createSearchHits(List.of());
    SearchHits<DocChunkDocument> emptyHits2 = createSearchHits(List.of());

    when(elasticsearchOperations.search(any(NativeQuery.class), eq(DocChunkDocument.class)))
        .thenReturn(emptyHits1, emptyHits2);

    List<ScoredDocChunkVO> results = service.hybridSearch(command);

    assertThat(results).isEmpty();
    verify(rerankGateway, never()).rerank(any());
  }

  @Test
  @DisplayName("文档级多样性上限：单文档切片刷屏时被截断，为其他文档腾出召回位")
  void testMaxChunksPerDocumentDiversity() {
    Long wsId = 100L;
    aiProperties.getRag().setMaxChunksPerDocument(2);
    HybridSearchCommand command = HybridSearchCommand.of(wsId, "脚本", 5);

    List<DocChunkDocument> bm25Docs = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      bm25Docs.add(
          DocChunkDocument.builder().id("1_" + i).workspaceId("100").documentId("1").build());
    }
    for (int i = 0; i < 3; i++) {
      bm25Docs.add(
          DocChunkDocument.builder().id("2_" + i).workspaceId("100").documentId("2").build());
    }
    DocChunkDocument otherDoc =
        DocChunkDocument.builder().id("3_0").workspaceId("100").documentId("3").build();
    bm25Docs.add(otherDoc);

    SearchHits<DocChunkDocument> bm25Hits = createSearchHits(bm25Docs);
    SearchHits<DocChunkDocument> knnHits = createSearchHits(List.of());
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(DocChunkDocument.class)))
        .thenReturn(bm25Hits, knnHits);

    when(rerankGateway.getStrategy()).thenReturn(RerankStrategyEnum.NONE);
    when(rerankGateway.rerank(any(RerankCommand.class)))
        .thenAnswer(
            invocation -> {
              RerankCommand cmd = invocation.getArgument(0);
              List<ScoredDocChunkVO> scored = new ArrayList<>();
              for (int i = 0; i < cmd.candidates().size(); i++) {
                scored.add(ScoredDocChunkVO.of(cmd.candidates().get(i), 1.0 - i * 0.01, i + 1));
              }
              return scored;
            });

    List<ScoredDocChunkVO> results = service.hybridSearch(command);

    // 单文档最多 2 条：doc-1 只有 2 条入围，doc-2 有 2 条，doc-3 有 1 条，共 5 条
    assertThat(results).hasSize(5);
    assertThat(results.stream().filter(r -> "1".equals(r.chunk().getDocumentId()))).hasSize(2);
    assertThat(results.stream().filter(r -> "2".equals(r.chunk().getDocumentId()))).hasSize(2);
    assertThat(results.stream().filter(r -> "3".equals(r.chunk().getDocumentId()))).hasSize(1);
  }

  @Test
  @DisplayName("多样性两阶段回填：当候选集中仅有单一文档时，首轮受限后第二阶段自动从回填池补齐至目标 TopK")
  void testDiversityBackfillWhenSingleDocument() {
    Long wsId = 100L;
    aiProperties.getRag().setMaxChunksPerDocument(2);
    HybridSearchCommand command = HybridSearchCommand.of(wsId, "数据库设计", 5);

    List<DocChunkDocument> bm25Docs = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      bm25Docs.add(
          DocChunkDocument.builder().id("1_" + i).workspaceId("100").documentId("1").build());
    }

    SearchHits<DocChunkDocument> bm25Hits = createSearchHits(bm25Docs);
    SearchHits<DocChunkDocument> knnHits = createSearchHits(List.of());
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(DocChunkDocument.class)))
        .thenReturn(bm25Hits, knnHits);

    when(rerankGateway.getStrategy()).thenReturn(RerankStrategyEnum.NONE);
    when(rerankGateway.rerank(any(RerankCommand.class)))
        .thenAnswer(
            invocation -> {
              RerankCommand cmd = invocation.getArgument(0);
              List<ScoredDocChunkVO> scored = new ArrayList<>();
              for (int i = 0; i < cmd.candidates().size(); i++) {
                scored.add(ScoredDocChunkVO.of(cmd.candidates().get(i), 1.0 - i * 0.01, i + 1));
              }
              return scored;
            });

    List<ScoredDocChunkVO> results = service.hybridSearch(command);

    // 虽然单文档限制为 2，但由于缺乏其他文档且目标 TopK 为 5，第二阶段回填机制自动补齐至 5 条，
    // 彻底杜绝单文档内容全貌被误杀丢弃的问题
    assertThat(results).hasSize(5);
    assertThat(results.stream().filter(r -> "1".equals(r.chunk().getDocumentId()))).hasSize(5);
  }

  @Test
  @DisplayName("表结构宽松阈值：table 切片或包含数据表结构定义时，即使向量余弦较低也不会被严格阈值误杀")
  void testTableChunkRelaxedThreshold() {
    Long wsId = 100L;
    // 构造查询向量与候选向量，余弦相似度为 0.25 (低于普通 0.30 阈值，但高于表结构宽松阈值 0.195)
    float[] queryVector = new float[] {1.0f, 0.0f};
    HybridSearchCommand command = HybridSearchCommand.of(wsId, "数据表结构", queryVector, 5);

    // 两个切片，余弦均为 0.25：一个是 table 类型，一个是普通 text 类型
    // 向量为 [0.25, sqrt(1 - 0.25^2)] 保证模长为 1，dot product = 0.25
    float y = (float) Math.sqrt(1.0 - 0.25 * 0.25);
    float[] vectorWith025Cosine = new float[] {0.25f, y};

    DocChunkDocument tableDoc =
        DocChunkDocument.builder()
            .id("t_1")
            .workspaceId("100")
            .documentId("1")
            .chunkType("table")
            .content("【数据表结构定义】tb_user 用户表")
            .contentVector(vectorWith025Cosine)
            .build();

    DocChunkDocument normalDoc =
        DocChunkDocument.builder()
            .id("n_1")
            .workspaceId("100")
            .documentId("2")
            .chunkType("text")
            .content("普通无关描述文本")
            .contentVector(vectorWith025Cosine)
            .build();

    SearchHits<DocChunkDocument> bm25Hits = createSearchHits(List.of(tableDoc, normalDoc));
    SearchHits<DocChunkDocument> knnHits = createSearchHits(List.of());
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(DocChunkDocument.class)))
        .thenReturn(bm25Hits, knnHits);

    when(rerankGateway.getStrategy()).thenReturn(RerankStrategyEnum.NONE);
    when(rerankGateway.rerank(any(RerankCommand.class)))
        .thenReturn(
            List.of(
                // 模拟原始排名：让 normalDoc 排名第 10 (非 <=5)，tableDoc 排名第 6
                ScoredDocChunkVO.of(normalDoc, 0.85, 10), ScoredDocChunkVO.of(tableDoc, 0.80, 6)));

    List<ScoredDocChunkVO> results = service.hybridSearch(command);

    // normalDoc 因余弦 0.25 < 0.30 被过滤，而 tableDoc 因表结构保护宽松阈值 (0.30 * 0.65 = 0.195) 成功保留
    assertThat(results).hasSize(1);
    assertThat(results.getFirst().chunk().getId()).isEqualTo("t_1");
  }

  @Test
  @DisplayName("真实 Cosine 打分：模拟重排分数被真实向量相似度覆盖，低相似度切片按阈值过滤")
  void testRealCosineScoringAndThreshold() {
    Long wsId = 100L;
    float[] queryVector = new float[] {1.0f, 0.0f};
    HybridSearchCommand command = HybridSearchCommand.of(wsId, "脚本", queryVector, 8);

    // 与查询向量完全同向 -> 余弦 1.0；正交 -> 余弦 0.0
    DocChunkDocument relevant =
        DocChunkDocument.builder()
            .id("7_0")
            .workspaceId("100")
            .documentId("7")
            .contentVector(new float[] {1.0f, 0.0f})
            .build();
    DocChunkDocument irrelevant =
        DocChunkDocument.builder()
            .id("8_0")
            .workspaceId("100")
            .documentId("8")
            .contentVector(new float[] {0.0f, 1.0f})
            .build();

    SearchHits<DocChunkDocument> bm25Hits = createSearchHits(List.of(relevant, irrelevant));
    SearchHits<DocChunkDocument> knnHits = createSearchHits(List.of());
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(DocChunkDocument.class)))
        .thenReturn(bm25Hits, knnHits);

    when(rerankGateway.getStrategy()).thenReturn(RerankStrategyEnum.NONE);
    // 适配器编造的模拟分数 (0.9 / 0.8) 必须被真实余弦覆盖，且低相似度切片需被阈值过滤
    when(rerankGateway.rerank(any(RerankCommand.class)))
        .thenReturn(
            List.of(
                ScoredDocChunkVO.of(relevant, 0.90, 1), ScoredDocChunkVO.of(irrelevant, 0.80, 2)));

    List<ScoredDocChunkVO> results = service.hybridSearch(command);

    assertThat(results).hasSize(1);
    assertThat(results.getFirst().chunk().getId()).isEqualTo("7_0");
    assertThat(results.getFirst().score()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("配额隔离混合检索：仅将向量检索结果交由 Rerank 取前 15 条，额外保留 5 条 BM25 词法结果，共 20 条输出")
  void testQuotaBasedHybridSearch_15VectorRerankPlus5LexicalPreserved() {
    Long wsId = 100L;
    aiProperties.getRag().setTopK(20);
    aiProperties.getRag().setVectorTopK(15);
    aiProperties.getRag().setLexicalTopK(5);
    aiProperties.getRag().setMaxChunksPerDocument(0); // 关闭单文档限制，专注于验证配额数量

    // 构造 20 个向量命中切片 (vec_1 .. vec_20)
    List<DocChunkDocument> knnDocs = new ArrayList<>();
    for (int i = 1; i <= 20; i++) {
      knnDocs.add(
          DocChunkDocument.builder()
              .id("vec_" + i)
              .workspaceId("100")
              .documentId("doc_v_" + i)
              .title("向量命中 " + i)
              .build());
    }

    // 构造 10 个 BM25 命中切片：前两个与向量切片重复 (vec_1, vec_2)，后 8 个为独立词法命中 (bm_3 .. bm_10)
    List<DocChunkDocument> bm25Docs = new ArrayList<>();
    bm25Docs.add(DocChunkDocument.builder().id("vec_1").workspaceId("100").title("重叠切片1").build());
    bm25Docs.add(DocChunkDocument.builder().id("vec_2").workspaceId("100").title("重叠切片2").build());
    for (int i = 3; i <= 10; i++) {
      bm25Docs.add(
          DocChunkDocument.builder()
              .id("bm_" + i)
              .workspaceId("100")
              .documentId("doc_bm_" + i)
              .title("词法命中 " + i)
              .build());
    }

    SearchHits<DocChunkDocument> bm25Hits = createSearchHits(bm25Docs);
    SearchHits<DocChunkDocument> knnHits = createSearchHits(knnDocs);
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(DocChunkDocument.class)))
        .thenReturn(bm25Hits, knnHits);

    when(rerankGateway.getStrategy()).thenReturn(RerankStrategyEnum.CROSS_ENCODER);

    ArgumentCaptor<RerankCommand> rerankCaptor = ArgumentCaptor.forClass(RerankCommand.class);
    when(rerankGateway.rerank(rerankCaptor.capture()))
        .thenAnswer(
            invocation -> {
              RerankCommand cmd = invocation.getArgument(0);
              List<ScoredDocChunkVO> scored = new ArrayList<>();
              for (int i = 0; i < cmd.candidates().size(); i++) {
                scored.add(ScoredDocChunkVO.of(cmd.candidates().get(i), 0.99 - i * 0.01, i + 1));
              }
              return scored;
            });

    HybridSearchCommand command =
        HybridSearchCommand.of(wsId, "深度排序与精确词法结合", new float[] {0.1f}, 20);
    List<ScoredDocChunkVO> results = service.hybridSearch(command);

    // 1. 验证总输出精确为 20 条
    assertThat(results).hasSize(20);

    // 2. 验证交付给 Rerank 模型的候选集【只包含向量召回的切片】，完全不掺杂 BM25 的多余干扰
    RerankCommand sentToRerank = rerankCaptor.getValue();
    assertThat(sentToRerank.candidates()).hasSize(20);
    assertThat(sentToRerank.candidates().stream().map(DocChunkDocument::getId))
        .allMatch(id -> id.startsWith("vec_"));

    // 3. 验证前 15 条全部来自向量精排 (vec_1 .. vec_15)
    List<String> top15Ids = results.subList(0, 15).stream().map(r -> r.chunk().getId()).toList();
    for (int i = 1; i <= 15; i++) {
      assertThat(top15Ids).contains("vec_" + i);
    }

    // 4. 验证后 5 条全部来自 BM25 精确词法保底，且排除了已在前 15 条出现的 vec_1, vec_2，按序顺延获取 bm_3 .. bm_7
    List<String> last5Ids = results.subList(15, 20).stream().map(r -> r.chunk().getId()).toList();
    assertThat(last5Ids).containsExactly("bm_3", "bm_4", "bm_5", "bm_6", "bm_7");

    // 5. 验证标记哪些是 RERANK，哪些是 BM25 召回的数据
    assertThat(results.subList(0, 15))
        .allSatisfy(r -> assertThat(r.recallSource()).isEqualTo("RERANK"));
    assertThat(results.subList(15, 20))
        .allSatisfy(r -> assertThat(r.recallSource()).isEqualTo("BM25"));
  }

  @Test
  @DisplayName("自然语言提问检索：对于包含意图词与停用词的长提问（如'S16 有哪些数据库表 要返回表名和表注释'），BM25 不设硬编码 minimumShouldMatch")
  void testConversationalQueryNaturalLanguageLexicalRetrieval() {
    Long wsId = 100L;
    String conversationalQuery = "S16 有哪些数据库表 要返回表名和表注释";

    DocChunkDocument tableChunk1 =
        DocChunkDocument.builder()
            .id("doc_s16_1")
            .workspaceId("100")
            .documentId("doc_1")
            .title("S16 数据库设计说明书")
            .contextPrefix("[文档: 《S16 数据库设计说明书》] > [章节: 用户表] > [类型: table]")
            .content("表名: tb_user (用户表) | 字段: id, username | 注释: 系统用户主表")
            .build();

    DocChunkDocument tableChunk2 =
        DocChunkDocument.builder()
            .id("doc_s16_2")
            .workspaceId("100")
            .documentId("doc_1")
            .title("S16 数据库设计说明书")
            .contextPrefix("[文档: 《S16 数据库设计说明书》] > [章节: 订单表] > [类型: table]")
            .content("表名: tb_order (订单表) | 字段: id, order_no | 注释: 交易订单表")
            .build();

    SearchHits<DocChunkDocument> bm25Hits = createSearchHits(List.of(tableChunk1, tableChunk2));
    SearchHits<DocChunkDocument> knnHits = createSearchHits(List.of());

    when(elasticsearchOperations.search(any(NativeQuery.class), eq(DocChunkDocument.class)))
        .thenReturn(bm25Hits, knnHits);

    when(rerankGateway.getStrategy()).thenReturn(RerankStrategyEnum.NONE);
    when(rerankGateway.rerank(any(RerankCommand.class)))
        .thenAnswer(
            invocation -> {
              RerankCommand cmd = invocation.getArgument(0);
              return cmd.candidates().stream().map(c -> ScoredDocChunkVO.of(c, 0.9, 1)).toList();
            });

    when(embeddingGateway.embed(conversationalQuery)).thenReturn(new float[] {0.1f});

    HybridSearchCommand command = HybridSearchCommand.of(wsId, conversationalQuery, 5);
    List<ScoredDocChunkVO> results = service.hybridSearch(command);

    // 校验 BM25 查询构建：确保未设置 minimumShouldMatch("70%")，避免将自然语言问答中的停用词/指令词导致 0 条命中
    ArgumentCaptor<NativeQuery> queryCaptor = ArgumentCaptor.forClass(NativeQuery.class);
    verify(elasticsearchOperations, org.mockito.Mockito.times(2))
        .search(queryCaptor.capture(), eq(DocChunkDocument.class));

    NativeQuery lexicalQuery = queryCaptor.getAllValues().get(0);
    var multiMatch = lexicalQuery.getQuery().bool().must().getFirst().multiMatch();
    assertThat(multiMatch.query()).isEqualTo("S16 数据库表 表名 表注释");
    assertThat(multiMatch.minimumShouldMatch()).isNull();
    assertThat(multiMatch.fields())
        .containsExactly("content^3", "contextPrefix^2.5", "title^1", "headerPath^1.5");

    // 校验召回结果正常返回，未被误杀
    assertThat(results).hasSize(2);
    assertThat(results.stream().map(r -> r.chunk().getId()))
        .containsExactly("doc_s16_1", "doc_s16_2");
  }

  @Test
  @DisplayName("ACL 权限过滤：工作空间管理员进行检索时跳过可见性过滤")
  void testAdminHybridSearchSkipsAclFilter() {
    Long wsId = 100L;
    HybridSearchCommand command =
        new HybridSearchCommand(wsId, "通用检索", null, 5, null, null, List.of(), null, 999L, true);

    DocChunkDocument doc =
        DocChunkDocument.builder()
            .id("doc_admin_1")
            .workspaceId("100")
            .documentId("doc_1")
            .title("机密文件")
            .content("机密内容")
            .visibility("PRIVATE")
            .createBy("123")
            .build();

    SearchHits<DocChunkDocument> hits = createSearchHits(List.of(doc));
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(DocChunkDocument.class)))
        .thenReturn(hits, createSearchHits(List.of()));
    when(rerankGateway.getStrategy()).thenReturn(RerankStrategyEnum.NONE);
    when(rerankGateway.rerank(any(RerankCommand.class)))
        .thenAnswer(inv -> List.of(ScoredDocChunkVO.of(doc, 0.9, 1)));

    List<ScoredDocChunkVO> results = service.hybridSearch(command);
    assertThat(results).hasSize(1);

    ArgumentCaptor<NativeQuery> queryCaptor = ArgumentCaptor.forClass(NativeQuery.class);
    verify(elasticsearchOperations, org.mockito.Mockito.atLeastOnce())
        .search(queryCaptor.capture(), eq(DocChunkDocument.class));

    NativeQuery lexicalQuery = queryCaptor.getAllValues().get(0);
    var filters = lexicalQuery.getQuery().bool().filter();
    // 管理员仅有工作区过滤，无 ACL visibility 复合 should 过滤
    boolean hasAclFilter =
        filters.stream().anyMatch(f -> f.isBool() && f.bool().minimumShouldMatch() != null);
    assertThat(hasAclFilter).isFalse();
  }

  @Test
  @DisplayName("ACL 权限过滤：普通成员进行检索时自动附加 PUBLIC/INTERNAL/自己PRIVATE/历史缺失字段 复合过滤")
  void testNonAdminHybridSearchIncludesAclFilter() {
    Long wsId = 100L;
    Long userId = 888L;
    HybridSearchCommand command =
        new HybridSearchCommand(wsId, "通用检索", null, 5, null, null, List.of(), null, userId, false);

    DocChunkDocument doc =
        DocChunkDocument.builder()
            .id("doc_user_1")
            .workspaceId("100")
            .documentId("doc_1")
            .title("公开规范")
            .content("公开规范内容")
            .visibility("INTERNAL")
            .createBy("123")
            .build();

    SearchHits<DocChunkDocument> hits = createSearchHits(List.of(doc));
    when(elasticsearchOperations.search(any(NativeQuery.class), eq(DocChunkDocument.class)))
        .thenReturn(hits, createSearchHits(List.of()));
    when(rerankGateway.getStrategy()).thenReturn(RerankStrategyEnum.NONE);
    when(rerankGateway.rerank(any(RerankCommand.class)))
        .thenAnswer(inv -> List.of(ScoredDocChunkVO.of(doc, 0.9, 1)));

    List<ScoredDocChunkVO> results = service.hybridSearch(command);
    assertThat(results).hasSize(1);

    ArgumentCaptor<NativeQuery> queryCaptor = ArgumentCaptor.forClass(NativeQuery.class);
    verify(elasticsearchOperations, org.mockito.Mockito.atLeastOnce())
        .search(queryCaptor.capture(), eq(DocChunkDocument.class));

    NativeQuery lexicalQuery = queryCaptor.getAllValues().get(0);
    var filters = lexicalQuery.getQuery().bool().filter();
    // 普通成员必须包含 ACL visibility 复合 should 过滤 (minimumShouldMatch="1")
    var aclFilterOpt =
        filters.stream()
            .filter(f -> f.isBool() && "1".equals(f.bool().minimumShouldMatch()))
            .findFirst();
    assertThat(aclFilterOpt).isPresent();
    var shouldClauses = aclFilterOpt.get().bool().should();
    assertThat(shouldClauses).hasSize(3); // 1. PUBLIC/INTERNAL, 2. 自己PRIVATE, 3. 历史无visibility
  }
}
