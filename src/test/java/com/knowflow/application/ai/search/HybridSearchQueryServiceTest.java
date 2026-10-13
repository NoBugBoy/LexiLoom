package com.knowflow.application.ai.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowflow.application.ai.config.AiProperties;
import com.knowflow.application.ai.enums.RerankStrategyEnum;
import com.knowflow.application.ai.gateway.embedding.EmbeddingGateway;
import com.knowflow.application.ai.gateway.rerank.RerankCommand;
import com.knowflow.application.ai.gateway.rerank.RerankGateway;
import com.knowflow.application.ai.gateway.rerank.ScoredDocChunkVO;
import com.knowflow.application.ai.model.search.DocChunkDocument;
import com.knowflow.application.exception.BusinessException;
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
    assertThat(results.get(0).chunk().getId()).isEqualTo("1_0");
    assertThat(results.get(1).chunk().getId()).isEqualTo("2_0");

    verify(embeddingGateway).embed(query);

    // 校验两次 ES 查询均传递了 NativeQuery
    ArgumentCaptor<NativeQuery> queryCaptor = ArgumentCaptor.forClass(NativeQuery.class);
    verify(elasticsearchOperations, org.mockito.Mockito.times(2))
        .search(queryCaptor.capture(), eq(DocChunkDocument.class));

    List<NativeQuery> queries = queryCaptor.getAllValues();
    assertThat(queries).hasSize(2);
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
}
