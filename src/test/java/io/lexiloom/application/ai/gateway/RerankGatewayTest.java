package io.lexiloom.application.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.enums.RerankStrategyEnum;
import io.lexiloom.application.ai.gateway.rerank.CrossEncoderRerankAdapter;
import io.lexiloom.application.ai.gateway.rerank.DefaultRerankGateway;
import io.lexiloom.application.ai.gateway.rerank.MockRerankAdapter;
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
  private MockRerankAdapter mockAdapter;
  private DefaultRerankGateway gateway;

  @BeforeEach
  void setUp() {
    properties = new AiProperties();
    noOpAdapter = new NoOpRerankAdapter();
    rrfAdapter = new RrfRerankAdapter();
    crossEncoderAdapter = new CrossEncoderRerankAdapter(properties);
    mockAdapter = new MockRerankAdapter();

    gateway =
        new DefaultRerankGateway(
            List.of(noOpAdapter, rrfAdapter, crossEncoderAdapter, mockAdapter), properties);
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

    // 空输入测试
    assertThat(noOpAdapter.rerank(null)).isEmpty();
    assertThat(noOpAdapter.rerank(RerankCommand.of("空", List.of(), 5))).isEmpty();
  }

  @Test
  @DisplayName("RrfRerankAdapter：基于倒数排名融合算法计算公式准确打分与排序")
  void testRrfRerankAdapterCalculation() {
    DocChunkDocument c1 = createChunk("doc-1", "标题1", "内容1");
    DocChunkDocument c2 = createChunk("doc-2", "标题2", "内容2");

    RerankCommand command = RerankCommand.of("query", List.of(c1, c2), 5);
    List<ScoredDocChunkVO> results = rrfAdapter.rerank(command);

    assertThat(results).hasSize(2);
    // 第一名: 1.0 / (60 + 1) = 1/61
    // 第二名: 1.0 / (60 + 2) = 1/62
    double expectedScore1 = 1.0 / 61.0;
    double expectedScore2 = 1.0 / 62.0;

    assertThat(results.get(0).chunk().getId()).isEqualTo("doc-1");
    assertThat(results.get(0).score())
        .isCloseTo(expectedScore1, org.assertj.core.data.Offset.offset(0.00001));
    assertThat(results.get(0).originalRank()).isEqualTo(1);

    assertThat(results.get(1).chunk().getId()).isEqualTo("doc-2");
    assertThat(results.get(1).score())
        .isCloseTo(expectedScore2, org.assertj.core.data.Offset.offset(0.00001));
    assertThat(results.get(1).originalRank()).isEqualTo(2);

    assertThat(rrfAdapter.isEnabled()).isTrue();
    assertThat(rrfAdapter.getStrategy()).isEqualTo(RerankStrategyEnum.RRF);
  }

  @Test
  @DisplayName("RrfRerankAdapter：多路重复候选切块得分累加合并")
  void testRrfRerankAdapterDuplicateAggregation() {
    DocChunkDocument c1 = createChunk("doc-1", "标题1", "内容1");
    DocChunkDocument c2 = createChunk("doc-2", "标题2", "内容2");
    // 模拟多路召回：doc-2 同时出现在第 2 位与第 3 位
    DocChunkDocument c2Dup = createChunk("doc-2", "标题2", "内容2");

    RerankCommand command = RerankCommand.of("query", List.of(c1, c2, c2Dup), 5);
    List<ScoredDocChunkVO> results = rrfAdapter.rerank(command);

    // doc-2 得分为 1/(60+2) + 1/(60+3) = 1/62 + 1/63 ≈ 0.016129 + 0.015873 = 0.032002
    // doc-1 得分为 1/(60+1) = 1/61 ≈ 0.016393
    // 因此 doc-2 累加后反超 doc-1
    assertThat(results).hasSize(2);
    assertThat(results.get(0).chunk().getId()).isEqualTo("doc-2");
    assertThat(results.get(1).chunk().getId()).isEqualTo("doc-1");
  }

  @Test
  @DisplayName("CrossEncoderRerankAdapter：根据标题与正文关键词匹配进行启发式深度精排")
  void testCrossEncoderRerankAdapter() {
    DocChunkDocument c1 = createChunk("doc-1", "无关标题", "无关正文");
    DocChunkDocument c2 = createChunk("doc-2", "知识库系统架构设计", "Spring AI 集成");

    RerankCommand command = RerankCommand.of("知识库", List.of(c1, c2), 2);
    List<ScoredDocChunkVO> results = crossEncoderAdapter.rerank(command);

    assertThat(results).hasSize(2);
    // c2 命中标题中的“知识库”，精排得分必然高于 c1
    assertThat(results.get(0).chunk().getId()).isEqualTo("doc-2");
    assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());

    assertThat(crossEncoderAdapter.getStrategy()).isEqualTo(RerankStrategyEnum.CROSS_ENCODER);
  }

  @Test
  @DisplayName("MockRerankAdapter：Mock 模式按给定 topK 生成稳定测试打分")
  void testMockRerankAdapter() {
    DocChunkDocument c1 = createChunk("1", "A", "Content A");
    DocChunkDocument c2 = createChunk("2", "B", "Content B");

    RerankCommand command = RerankCommand.of("query", List.of(c1, c2), 1);
    List<ScoredDocChunkVO> results = mockAdapter.rerank(command);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).score()).isEqualTo(0.95);
    assertThat(mockAdapter.getStrategy()).isEqualTo(RerankStrategyEnum.MOCK);
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
}
