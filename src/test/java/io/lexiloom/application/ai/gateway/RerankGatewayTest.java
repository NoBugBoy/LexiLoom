package io.lexiloom.application.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.enums.RerankStrategyEnum;
import io.lexiloom.application.ai.gateway.rerank.CrossEncoderRerankAdapter;
import io.lexiloom.application.ai.gateway.rerank.DefaultRerankGateway;
import io.lexiloom.application.ai.gateway.rerank.NoOpRerankAdapter;
import io.lexiloom.application.ai.gateway.rerank.RerankCommand;
import io.lexiloom.application.ai.gateway.rerank.RrfRerankAdapter;
import io.lexiloom.application.ai.gateway.rerank.ScoredDocChunkVO;
import io.lexiloom.application.ai.model.search.DocChunkDocument;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RerankGatewayTest {

  private AiProperties properties;
  private NoOpRerankAdapter noOpAdapter;
  private RrfRerankAdapter rrfAdapter;
  private CrossEncoderRerankAdapter crossEncoderAdapter;
  private DefaultRerankGateway gateway;

  @BeforeEach
  void setUp() {
    properties = new AiProperties();
    noOpAdapter = new NoOpRerankAdapter();
    rrfAdapter = new RrfRerankAdapter();
    crossEncoderAdapter = new CrossEncoderRerankAdapter(properties);

    gateway =
        new DefaultRerankGateway(List.of(noOpAdapter, rrfAdapter, crossEncoderAdapter), properties);
  }

  private DocChunkDocument createChunk(String id, String title, String content) {
    return DocChunkDocument.builder().id(id).title(title).content(content).build();
  }

  @Test
  @DisplayName("NoOpRerankAdapter：零额外开销透传并截取 Top-K，且不编造相似度分数")
  void testNoOpRerankAdapter() {
    DocChunkDocument c1 = createChunk("1", "A", "Content A");
    DocChunkDocument c2 = createChunk("2", "B", "Content B");
    DocChunkDocument c3 = createChunk("3", "C", "Content C");

    RerankCommand command = RerankCommand.of("测试", List.of(c1, c2, c3), 2);
    List<ScoredDocChunkVO> results = noOpAdapter.rerank(command);

    assertThat(results).hasSize(2);
    assertThat(results.get(0).chunk().getId()).isEqualTo("1");
    assertThat(results.get(0).score()).isEqualTo(NoOpRerankAdapter.NO_SCORE);
    assertThat(results.get(0).originalRank()).isEqualTo(1);
    assertThat(results.get(1).chunk().getId()).isEqualTo("2");
    assertThat(results.get(1).score()).isEqualTo(NoOpRerankAdapter.NO_SCORE);
    assertThat(results.get(1).originalRank()).isEqualTo(2);

    assertThat(noOpAdapter.isEnabled()).isFalse();
    assertThat(noOpAdapter.getStrategy()).isEqualTo(RerankStrategyEnum.NONE);
  }

  @Test
  @DisplayName("NoOpRerankAdapter：空候选集或空命令时安全返回空列表")
  void testNoOpRerankAdapterEmpty() {
    assertThat(noOpAdapter.rerank(null)).isEmpty();
    assertThat(noOpAdapter.rerank(RerankCommand.of("q", List.of(), 5))).isEmpty();
  }

  @Test
  @DisplayName("RrfRerankAdapter：空候选集时安全返回空列表")
  void testRrfRerankAdapterEmpty() {
    assertThat(rrfAdapter.rerank(null)).isEmpty();
    assertThat(rrfAdapter.rerank(RerankCommand.of("q", List.of(), 5))).isEmpty();
    assertThat(rrfAdapter.isEnabled()).isTrue();
    assertThat(rrfAdapter.getStrategy()).isEqualTo(RerankStrategyEnum.RRF);
  }

  @Test
  @DisplayName("RrfRerankAdapter：单路单项输入按倒数排名 1/(60+rank) 严格计算得分")
  void testRrfRerankSingleList() {
    DocChunkDocument c1 = createChunk("1", "A", "Content A");
    DocChunkDocument c2 = createChunk("2", "B", "Content B");

    RerankCommand command = RerankCommand.of("query", List.of(c1, c2), 2);
    List<ScoredDocChunkVO> results = rrfAdapter.rerank(command);

    assertThat(results).hasSize(2);
    // 第一名得分 1 / (60 + 1) ≈ 0.016393
    assertThat(results.get(0).chunk().getId()).isEqualTo("1");
    assertThat(results.get(0).score())
        .isCloseTo(1.0 / 61.0, org.assertj.core.data.Offset.offset(1e-6));
    assertThat(results.get(0).originalRank()).isEqualTo(1);

    // 第二名得分 1 / (60 + 2) ≈ 0.016129
    assertThat(results.get(1).chunk().getId()).isEqualTo("2");
    assertThat(results.get(1).score())
        .isCloseTo(1.0 / 62.0, org.assertj.core.data.Offset.offset(1e-6));
    assertThat(results.get(1).originalRank()).isEqualTo(2);
  }

  @Test
  @DisplayName("RrfRerankAdapter：双路多重命中切片累加倒数排名反超单路第 1 名")
  void testRrfRerankDualHitsAccumulation() {
    DocChunkDocument c1 = createChunk("doc-1", "A", "Content A");
    DocChunkDocument c2 = createChunk("doc-2", "B", "Content B");

    // 模拟初筛双路交织序列：doc-1 仅出现 1 次 (Rank 1)，doc-2 出现 2 次 (Rank 2 与 Rank 3)
    List<DocChunkDocument> dualPathSequence = List.of(c1, c2, c2);

    RerankCommand command = RerankCommand.of("query", dualPathSequence, 2);
    List<ScoredDocChunkVO> results = rrfAdapter.rerank(command);

    // doc-2 得分为 1/(60+2) + 1/(60+3) = 1/62 + 1/63 ≈ 0.016129 + 0.015873 = 0.032002
    // doc-1 得分为 1/(60+1) = 1/61 ≈ 0.016393
    // 因此 doc-2 累加后反超 doc-1
    assertThat(results).hasSize(2);
    assertThat(results.get(0).chunk().getId()).isEqualTo("doc-2");
    assertThat(results.get(1).chunk().getId()).isEqualTo("doc-1");
  }

  @Test
  @DisplayName("CrossEncoderRerankAdapter：未配置 API Key 时明确抛出配置异常")
  void testCrossEncoderRerankAdapterWithoutApiKeyThrows() {
    properties.getRerank().setApiKey("");

    DocChunkDocument c1 = createChunk("doc-1", "无关标题", "无关正文");
    RerankCommand command = RerankCommand.of("知识库", List.of(c1), 1);

    assertThatThrownBy(() -> crossEncoderAdapter.rerank(command))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("启用 CROSS_ENCODER 重排必须配置有效 API Key");
  }

  @Test
  @DisplayName("CrossEncoderRerankAdapter：配置 API Key 时成功调用 DashScope 远程接口并按评分重排")
  void testCrossEncoderRerankAdapter_RemoteDashScopeSuccess() {
    properties.getRerank().setApiKey("sk-mock-dashscope-key");
    org.springframework.web.client.RestClient restClient =
        org.mockito.Mockito.mock(org.springframework.web.client.RestClient.class);
    org.springframework.web.client.RestClient.RequestBodyUriSpec uriSpec =
        org.mockito.Mockito.mock(
            org.springframework.web.client.RestClient.RequestBodyUriSpec.class);
    org.springframework.web.client.RestClient.RequestBodySpec bodySpec =
        org.mockito.Mockito.mock(org.springframework.web.client.RestClient.RequestBodySpec.class);
    org.springframework.web.client.RestClient.ResponseSpec responseSpec =
        org.mockito.Mockito.mock(org.springframework.web.client.RestClient.ResponseSpec.class);

    org.mockito.Mockito.when(restClient.post()).thenReturn(uriSpec);
    org.mockito.Mockito.when(uriSpec.uri(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(bodySpec);
    org.mockito.Mockito.when(
            bodySpec.header(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(bodySpec);
    org.mockito.Mockito.when(bodySpec.body(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(bodySpec);
    org.mockito.Mockito.when(bodySpec.retrieve()).thenReturn(responseSpec);

    String mockResponseBody =
        """
        {
          "output": {
            "results": [
              { "index": 1, "relevance_score": 0.96 },
              { "index": 0, "relevance_score": 0.42 }
            ]
          },
          "usage": { "total_tokens": 40 }
        }
        """;
    org.mockito.Mockito.when(responseSpec.body(String.class)).thenReturn(mockResponseBody);

    CrossEncoderRerankAdapter adapter = new CrossEncoderRerankAdapter(properties, restClient, null);

    DocChunkDocument c1 = createChunk("doc-1", "标题1", "文本排序模型简介");
    DocChunkDocument c2 = createChunk("doc-2", "标题2", "量子力学入门");

    RerankCommand command = RerankCommand.of("排序模型", List.of(c1, c2), 2);
    List<ScoredDocChunkVO> results = adapter.rerank(command);

    assertThat(results).hasSize(2);
    // 远程 DashScope 明确打分 index=1 (c2) 得分为 0.96，因此排在第一
    assertThat(results.get(0).chunk().getId()).isEqualTo("doc-2");
    assertThat(results.get(0).score()).isEqualTo(0.96);
    assertThat(results.get(1).chunk().getId()).isEqualTo("doc-1");
    assertThat(results.get(1).score()).isEqualTo(0.42);
  }

  @Test
  @DisplayName("CrossEncoderRerankAdapter：配置 OpenAI 兼容端点时成功按兼容格式发起请求并解析")
  void testCrossEncoderRerankAdapter_OpenAiCompatibleSuccess() {
    properties.getRerank().setApiKey("sk-mock-key");
    properties.getRerank().setBaseUrl("https://maas.qianwenaiapi.com/compatible-api/v1/reranks");
    properties.getRerank().setModel("qwen3-rerank");

    org.springframework.web.client.RestClient restClient =
        org.mockito.Mockito.mock(org.springframework.web.client.RestClient.class);
    org.springframework.web.client.RestClient.RequestBodyUriSpec uriSpec =
        org.mockito.Mockito.mock(
            org.springframework.web.client.RestClient.RequestBodyUriSpec.class);
    org.springframework.web.client.RestClient.RequestBodySpec bodySpec =
        org.mockito.Mockito.mock(org.springframework.web.client.RestClient.RequestBodySpec.class);
    org.springframework.web.client.RestClient.ResponseSpec responseSpec =
        org.mockito.Mockito.mock(org.springframework.web.client.RestClient.ResponseSpec.class);

    org.mockito.Mockito.when(restClient.post()).thenReturn(uriSpec);
    org.mockito.Mockito.when(uriSpec.uri("https://maas.qianwenaiapi.com/compatible-api/v1/reranks"))
        .thenReturn(bodySpec);
    org.mockito.Mockito.when(
            bodySpec.header(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(bodySpec);
    org.mockito.Mockito.when(bodySpec.body(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(bodySpec);
    org.mockito.Mockito.when(bodySpec.retrieve()).thenReturn(responseSpec);

    String mockResponseBody =
        """
        {
          "id": "rerank-123",
          "object": "list",
          "results": [
            { "index": 0, "relevance_score": 0.88 }
          ]
        }
        """;
    org.mockito.Mockito.when(responseSpec.body(String.class)).thenReturn(mockResponseBody);

    CrossEncoderRerankAdapter adapter = new CrossEncoderRerankAdapter(properties, restClient, null);

    DocChunkDocument c1 = createChunk("doc-1", "标题1", "OpenAI兼容重排测试");
    RerankCommand command = RerankCommand.of("测试", List.of(c1), 1);
    List<ScoredDocChunkVO> results = adapter.rerank(command);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).chunk().getId()).isEqualTo("doc-1");
    assertThat(results.get(0).score()).isEqualTo(0.88);
  }

  @Test
  @DisplayName("CrossEncoderRerankAdapter：远程接口异常时抛出包装异常")
  void testCrossEncoderRerankAdapter_RemoteDashScopeError_Throws() {
    properties.getRerank().setApiKey("sk-mock-dashscope-key");
    org.springframework.web.client.RestClient restClient =
        org.mockito.Mockito.mock(org.springframework.web.client.RestClient.class);
    org.mockito.Mockito.when(restClient.post())
        .thenThrow(new RuntimeException("Connect timeout to DashScope"));

    CrossEncoderRerankAdapter adapter = new CrossEncoderRerankAdapter(properties, restClient, null);

    DocChunkDocument c1 = createChunk("doc-1", "无关标题", "无关正文");
    RerankCommand command = RerankCommand.of("知识库", List.of(c1), 1);

    assertThatThrownBy(() -> adapter.rerank(command))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("调用 DashScope Rerank 服务失败");
  }

  @Test
  @DisplayName("DefaultRerankGateway 路由：默认关闭状态下透传至 NoOp 适配器")
  void testDefaultGatewayDisabledRoutesToNoOp() {
    properties.getRerank().setEnabled(false);

    DocChunkDocument c1 = createChunk("1", "A", "Content A");
    RerankCommand command = RerankCommand.of("query", List.of(c1), 5);

    List<ScoredDocChunkVO> results = gateway.rerank(command);
    assertThat(results).hasSize(1);
    assertThat(gateway.isEnabled()).isFalse();
    assertThat(gateway.getStrategy()).isEqualTo(RerankStrategyEnum.NONE);
  }

  @Test
  @DisplayName("DefaultRerankGateway 路由：开启且指定 RRF 时正确路由")
  void testDefaultGatewayRoutesToRrf() {
    properties.getRerank().setEnabled(true);
    properties.getRerank().setStrategy(RerankStrategyEnum.RRF);

    DocChunkDocument c1 = createChunk("1", "A", "Content A");
    RerankCommand command = RerankCommand.of("query", List.of(c1), 5);

    List<ScoredDocChunkVO> results = gateway.rerank(command);
    assertThat(results).hasSize(1);
    assertThat(gateway.isEnabled()).isTrue();
    assertThat(gateway.getStrategy()).isEqualTo(RerankStrategyEnum.RRF);
  }

  @Test
  @DisplayName("DefaultRerankGateway 路由：开启且指定 CROSS_ENCODER 时正确路由")
  void testDefaultGatewayRoutesToCrossEncoder() {
    properties.getRerank().setEnabled(true);
    properties.getRerank().setStrategy(RerankStrategyEnum.CROSS_ENCODER);

    assertThat(gateway.isEnabled()).isTrue();
    assertThat(gateway.getStrategy()).isEqualTo(RerankStrategyEnum.CROSS_ENCODER);
  }

  @Test
  @DisplayName("DefaultRerankGateway 容错降级：配置无效或未命中策略时降级为 NONE 透传")
  void testDefaultGatewayFallbackToNoOp() {
    properties.getRerank().setEnabled(true);
    properties.getRerank().setStrategy(null); // 空策略

    assertThat(gateway.getStrategy()).isEqualTo(RerankStrategyEnum.NONE);
  }

  @Test
  @DisplayName("CrossEncoderRerankAdapter：发送至 Rerank 的文档必须包含 contextPrefix 上下文帽子，防止纯正文丢失实体与系统上下文")
  void testCrossEncoderRerankAdapter_IncludesContextPrefixInPayload() {
    properties.getRerank().setApiKey("sk-mock-key");
    properties.getRerank().setBaseUrl("https://maas.qianwenaiapi.com/compatible-api/v1/reranks");

    org.springframework.web.client.RestClient restClient =
        org.mockito.Mockito.mock(org.springframework.web.client.RestClient.class);
    org.springframework.web.client.RestClient.RequestBodyUriSpec uriSpec =
        org.mockito.Mockito.mock(
            org.springframework.web.client.RestClient.RequestBodyUriSpec.class);
    org.springframework.web.client.RestClient.RequestBodySpec bodySpec =
        org.mockito.Mockito.mock(org.springframework.web.client.RestClient.RequestBodySpec.class);
    org.springframework.web.client.RestClient.ResponseSpec responseSpec =
        org.mockito.Mockito.mock(org.springframework.web.client.RestClient.ResponseSpec.class);

    org.mockito.Mockito.when(restClient.post()).thenReturn(uriSpec);
    org.mockito.Mockito.when(uriSpec.uri(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(bodySpec);
    org.mockito.Mockito.when(
            bodySpec.header(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(bodySpec);

    org.mockito.ArgumentCaptor<String> bodyCaptor =
        org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.when(bodySpec.body(bodyCaptor.capture())).thenReturn(bodySpec);
    org.mockito.Mockito.when(bodySpec.retrieve()).thenReturn(responseSpec);

    String mockResponseBody =
        """
        {
          "output": {
            "results": [
              { "index": 0, "relevance_score": 0.95 }
            ]
          }
        }
        """;
    org.mockito.Mockito.when(responseSpec.body(String.class)).thenReturn(mockResponseBody);

    CrossEncoderRerankAdapter adapter = new CrossEncoderRerankAdapter(properties, restClient, null);

    DocChunkDocument chunk =
        DocChunkDocument.builder()
            .id("doc-1")
            .title("S16 数据库设计说明书")
            .contextPrefix("[文档: 《S16 数据库设计说明书》] > [章节: 用户表] > [类型: table]")
            .content("| 字段名 | 类型 |\n| user_id | bigint |")
            .build();

    RerankCommand command = RerankCommand.of("S16 数据库表", List.of(chunk), 1);
    adapter.rerank(command);

    String sentBody = bodyCaptor.getValue();
    assertThat(sentBody).contains("[文档: 《S16 数据库设计说明书》] > [章节: 用户表] > [类型: table]");
    assertThat(sentBody).contains("| 字段名 | 类型 |\\n| user_id | bigint |");
  }
}
