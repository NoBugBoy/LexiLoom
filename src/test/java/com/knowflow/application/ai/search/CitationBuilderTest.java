package com.knowflow.application.ai.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowflow.application.ai.gateway.rerank.ScoredDocChunkVO;
import com.knowflow.application.ai.model.search.DocChunkDocument;
import com.knowflow.application.ai.model.vo.CitationVO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CitationBuilderTest {

  private CitationBuilder citationBuilder;

  @BeforeEach
  void setUp() {
    citationBuilder = new CitationBuilder();
  }

  @Test
  @DisplayName("buildCitations：正确构建 1-indexed 引用溯源对象，并完整透传物理几何坐标")
  void testBuildCitations_Success() {
    DocChunkDocument doc1 =
        DocChunkDocument.builder()
            .id("100_0")
            .workspaceId("1")
            .documentId("100")
            .chunkIndex(0)
            .chunkId("chunk_p_0")
            .title("系统架构设计白皮书")
            .headerPath("第一章 总体拓扑 / 1.1 存储底座")
            .content("KnowFlow 采用 Elasticsearch 8.x 密集向量引擎进行双路召回。")
            .page(1)
            .bbox(List.of(10.5f, 20.0f, 300.0f, 85.5f))
            .chunkType("text")
            .anchor("段落_1")
            .build();

    DocChunkDocument doc2 =
        DocChunkDocument.builder()
            .id("100_1")
            .workspaceId("1")
            .documentId("100")
            .chunkIndex(1)
            .chunkId("chunk_p_1")
            .title("系统架构设计白皮书")
            .headerPath("第一章 总体拓扑 / 1.2 对象存储")
            .content("对象存储底座对接自建高性能 rustfs 集群。")
            .page(2)
            .bbox(List.of(15.0f, 30.0f, 280.0f, 90.0f))
            .chunkType("text")
            .anchor("段落_2")
            .build();

    ScoredDocChunkVO s1 = ScoredDocChunkVO.of(doc1, 0.95, 1);
    ScoredDocChunkVO s2 = ScoredDocChunkVO.of(doc2, 0.88, 2);

    List<CitationVO> citations = citationBuilder.buildCitations(List.of(s1, s2));

    assertThat(citations).hasSize(2);

    CitationVO c1 = citations.get(0);
    assertThat(c1.citationIndex()).isEqualTo(1);
    assertThat(c1.documentId()).isEqualTo(100L);
    assertThat(c1.docTitle()).isEqualTo("系统架构设计白皮书");
    assertThat(c1.headerPath()).isEqualTo("第一章 总体拓扑 / 1.1 存储底座");
    assertThat(c1.snippet()).isEqualTo("KnowFlow 采用 Elasticsearch 8.x 密集向量引擎进行双路召回。");
    assertThat(c1.score()).isEqualTo(0.95);
    assertThat(c1.page()).isEqualTo(1);
    assertThat(c1.bbox()).containsExactly(10.5f, 20.0f, 300.0f, 85.5f);
    assertThat(c1.chunkType()).isEqualTo("text");
    assertThat(c1.anchor()).isEqualTo("段落_1");

    CitationVO c2 = citations.get(1);
    assertThat(c2.citationIndex()).isEqualTo(2);
    assertThat(c2.documentId()).isEqualTo(100L);
    assertThat(c2.docTitle()).isEqualTo("系统架构设计白皮书");
    assertThat(c2.headerPath()).isEqualTo("第一章 总体拓扑 / 1.2 对象存储");
    assertThat(c2.snippet()).isEqualTo("对象存储底座对接自建高性能 rustfs 集群。");
    assertThat(c2.score()).isEqualTo(0.88);
    assertThat(c2.page()).isEqualTo(2);
    assertThat(c2.bbox()).containsExactly(15.0f, 30.0f, 280.0f, 90.0f);
  }

  @Test
  @DisplayName("buildCitations：召回切片保持完整内容不截断，确保表格及上下文完整无损")
  void testBuildCitations_PreservesFullContent() {
    String longText = "A".repeat(1500);
    DocChunkDocument doc =
        DocChunkDocument.builder()
            .id("10_0")
            .documentId("10")
            .title("测试")
            .content(longText)
            .build();

    List<CitationVO> citations =
        citationBuilder.buildCitations(List.of(ScoredDocChunkVO.of(doc, 0.9, 1)));

    assertThat(citations).hasSize(1);
    assertThat(citations.getFirst().snippet()).isEqualTo(longText);
  }

  @Test
  @DisplayName("buildCitations：空输入防呆防护")
  void testBuildCitations_EmptyAndNull() {
    assertThat(citationBuilder.buildCitations(null)).isEmpty();
    assertThat(citationBuilder.buildCitations(List.of())).isEmpty();
  }

  @Test
  @DisplayName("formatPromptContext：正确将引用对象排版组装为注入 LLM Prompt 的 Markdown 上下文")
  void testFormatPromptContext_Success() {
    CitationVO c1 =
        new CitationVO(
            1, 100L, "白皮书", "架构", "这是片段1的内容。", 0.9, 1, List.of(1f, 2f, 3f, 4f), "text", "段落_1");
    CitationVO c2 =
        new CitationVO(
            2, 100L, "白皮书", "存储", "这是片段2的内容。", 0.85, 2, List.of(5f, 6f, 7f, 8f), "text", "段落_2");

    String promptContext = citationBuilder.formatPromptContext(List.of(c1, c2));

    assertThat(promptContext).contains("参考片段 [1] (来源：《白皮书》 > 章节: 架构):");
    assertThat(promptContext).contains("这是片段1的内容。");
    assertThat(promptContext).contains("参考片段 [2] (来源：《白皮书》 > 章节: 存储):");
    assertThat(promptContext).contains("这是片段2的内容。");
  }

  @Test
  @DisplayName("formatPromptContext：无引用时返回友好兜底提示")
  void testFormatPromptContext_Empty() {
    assertThat(citationBuilder.formatPromptContext(null)).contains("无相关知识库参考资料");
    assertThat(citationBuilder.formatPromptContext(List.of())).contains("无相关知识库参考资料");
  }
}
