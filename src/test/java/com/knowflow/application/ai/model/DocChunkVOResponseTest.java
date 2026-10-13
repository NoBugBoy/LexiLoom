package com.knowflow.application.ai.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowflow.application.ai.model.request.AiChatRequest;
import com.knowflow.application.ai.model.response.AiChatChunkResponse;
import com.knowflow.application.ai.model.response.AiChatResponse;
import com.knowflow.application.ai.model.vo.CitationVO;
import com.knowflow.application.ai.model.vo.DocChunkVO;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocChunkVOResponseTest {

  @Test
  @DisplayName("验证 DocChunkVO 不可变性与字段防篡改拷贝")
  void testDocChunkVOImmutability() {
    List<Float> mutableBbox = new ArrayList<>();
    mutableBbox.add(10.0f);
    mutableBbox.add(20.0f);

    DocChunkVO vo =
        new DocChunkVO("101_0", 101L, 0, "第一章", "正文内容", 0.88, 1, mutableBbox, "text", "段落_1");

    mutableBbox.add(99.0f); // 外部修改不应影响内部不可变集合

    assertThat(vo.id()).isEqualTo("101_0");
    assertThat(vo.documentId()).isEqualTo(101L);
    assertThat(vo.chunkIndex()).isEqualTo(0);
    assertThat(vo.headerPath()).isEqualTo("第一章");
    assertThat(vo.content()).isEqualTo("正文内容");
    assertThat(vo.similarityScore()).isEqualTo(0.88);
    assertThat(vo.page()).isEqualTo(1);
    assertThat(vo.bbox()).containsExactly(10.0f, 20.0f);
    assertThat(vo.chunkType()).isEqualTo("text");
    assertThat(vo.anchor()).isEqualTo("段落_1");
  }

  @Test
  @DisplayName("验证 CitationVO 视觉高保真引用溯源")
  void testCitationVO() {
    CitationVO citation =
        new CitationVO(
            1,
            200L,
            "技术规范.pdf",
            "安全规范",
            "密态存储方案...",
            0.92,
            5,
            List.of(100f, 200f, 300f, 400f),
            "table",
            "表格_1");

    assertThat(citation.citationIndex()).isEqualTo(1);
    assertThat(citation.documentId()).isEqualTo(200L);
    assertThat(citation.docTitle()).isEqualTo("技术规范.pdf");
    assertThat(citation.headerPath()).isEqualTo("安全规范");
    assertThat(citation.snippet()).isEqualTo("密态存储方案...");
    assertThat(citation.score()).isEqualTo(0.92);
    assertThat(citation.page()).isEqualTo(5);
    assertThat(citation.bbox()).containsExactly(100f, 200f, 300f, 400f);
    assertThat(citation.chunkType()).isEqualTo("table");
    assertThat(citation.anchor()).isEqualTo("表格_1");
  }

  @Test
  @DisplayName("验证 AiChatRequest 与思维链开关解析")
  void testAiChatRequest() {
    AiChatRequest requestWithThinking = new AiChatRequest("解释下RAG架构", 1L, "sess_1", true);
    assertThat(requestWithThinking.message()).isEqualTo("解释下RAG架构");
    assertThat(requestWithThinking.workspaceId()).isEqualTo(1L);
    assertThat(requestWithThinking.sessionId()).isEqualTo("sess_1");
    assertThat(requestWithThinking.isThinkingEnabled()).isTrue();

    AiChatRequest requestWithoutThinking = new AiChatRequest("总结下本文", 1L, null, null);
    assertThat(requestWithoutThinking.isThinkingEnabled()).isFalse();
  }

  @Test
  @DisplayName("验证 AiChatChunkResponse 便捷工厂方法与不可变防御")
  void testAiChatChunkResponseFactories() {
    AiChatChunkResponse msg = AiChatChunkResponse.message("这是增量");
    assertThat(msg.type()).isEqualTo(AiChatChunkResponse.TYPE_MESSAGE);
    assertThat(msg.delta()).isEqualTo("这是增量");

    AiChatChunkResponse thinking = AiChatChunkResponse.thinking("思考中...");
    assertThat(thinking.type()).isEqualTo(AiChatChunkResponse.TYPE_THINKING);
    assertThat(thinking.delta()).isEqualTo("思考中...");

    CitationVO c = new CitationVO(1, 10L, "文档", "路径", "摘要", 0.9, 1, List.of(), "text", "段落_1");
    AiChatChunkResponse citationChunk = AiChatChunkResponse.citation(List.of(c));
    assertThat(citationChunk.type()).isEqualTo(AiChatChunkResponse.TYPE_CITATION);
    assertThat(citationChunk.citations()).hasSize(1);

    AiChatChunkResponse done = AiChatChunkResponse.done();
    assertThat(done.type()).isEqualTo(AiChatChunkResponse.TYPE_DONE);
  }

  @Test
  @DisplayName("验证 AiChatResponse 同步响应对象")
  void testAiChatResponse() {
    CitationVO c = new CitationVO(1, 10L, "文档", "路径", "摘要", 0.9, 1, List.of(), "text", "段落_1");
    AiChatResponse resp = AiChatResponse.of("回答内容", "思维链内容", List.of(c), 350L);

    assertThat(resp.content()).isEqualTo("回答内容");
    assertThat(resp.thinkingContent()).isEqualTo("思维链内容");
    assertThat(resp.citations()).containsExactly(c);
    assertThat(resp.latencyMs()).isEqualTo(350L);
  }
}
