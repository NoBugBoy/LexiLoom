package io.lexiloom.application.ai.model.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocChunkDocumentTest {

  @Test
  @DisplayName("验证 DocChunkDocument 实体构建与全字段赋值完整性（支持坐标与向量）")
  void testDocChunkDocumentBuildAndFields() {
    LocalDateTime now = LocalDateTime.now();
    float[] vector = new float[] {0.1f, 0.2f, 0.3f};
    List<Float> bbox = List.of(50.0f, 100.0f, 250.0f, 180.0f);

    DocChunkDocument doc =
        DocChunkDocument.builder()
            .id(DocChunkDocument.generateId(101L, 2))
            .workspaceId("ws_1")
            .documentId("101")
            .chunkIndex(2)
            .chunkId("chunk_p_2")
            .title("LexiLoom 架构白皮书")
            .headerPath("核心设计 / 向量检索")
            .content("这是 DeepDoc 提取出的原子段落原文")
            .contextPrefix("[文档: 《LexiLoom 架构白皮书》] > [章节: 核心设计 / 向量检索] > [类型: text]\n\n")
            .contentVector(vector)
            .page(3)
            .bbox(bbox)
            .chunkType("text")
            .anchor("段落_3")
            .tags(List.of("RAG", "DeepDoc"))
            .categoryId("cat_99")
            .updateTime(now)
            .build();

    assertThat(doc.getId()).isEqualTo("101_2");
    assertThat(doc.getWorkspaceId()).isEqualTo("ws_1");
    assertThat(doc.getDocumentId()).isEqualTo("101");
    assertThat(doc.getChunkIndex()).isEqualTo(2);
    assertThat(doc.getChunkId()).isEqualTo("chunk_p_2");
    assertThat(doc.getTitle()).isEqualTo("LexiLoom 架构白皮书");
    assertThat(doc.getHeaderPath()).isEqualTo("核心设计 / 向量检索");
    assertThat(doc.getContent()).isEqualTo("这是 DeepDoc 提取出的原子段落原文");
    assertThat(doc.getContextPrefix()).contains("[文档: 《LexiLoom 架构白皮书》]");
    assertThat(doc.getContentVector()).isEqualTo(vector);
    assertThat(doc.getPage()).isEqualTo(3);
    assertThat(doc.getBbox()).containsExactly(50.0f, 100.0f, 250.0f, 180.0f);
    assertThat(doc.getChunkType()).isEqualTo("text");
    assertThat(doc.getAnchor()).isEqualTo("段落_3");
    assertThat(doc.getTags()).containsExactly("RAG", "DeepDoc");
    assertThat(doc.getCategoryId()).isEqualTo("cat_99");
    assertThat(doc.getUpdateTime()).isEqualTo(now);
  }

  @Test
  @DisplayName("验证主键生成辅助方法 generateId")
  void testGenerateId() {
    assertThat(DocChunkDocument.generateId("doc_1", 0)).isEqualTo("doc_1_0");
    assertThat(DocChunkDocument.generateId(999L, 5)).isEqualTo("999_5");
    assertThat(DocChunkDocument.generateId(888L, null)).isEqualTo("888_0");
  }
}
