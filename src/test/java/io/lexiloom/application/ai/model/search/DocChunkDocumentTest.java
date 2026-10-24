package io.lexiloom.application.ai.model.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.MultiField;

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

  @Test
  @DisplayName("验证 headerPath 配置了 MultiField 支持 Text 全文分词与 Keyword 精准匹配")
  void testHeaderPathMultiFieldAnnotation() throws NoSuchFieldException {
    var field = DocChunkDocument.class.getDeclaredField("headerPath");
    MultiField multiField = field.getAnnotation(MultiField.class);
    assertThat(multiField).isNotNull();
    assertThat(multiField.mainField().type()).isEqualTo(FieldType.Text);
    assertThat(multiField.mainField().analyzer()).isEqualTo("ik_max_word");
    assertThat(multiField.mainField().searchAnalyzer()).isEqualTo("ik_smart");
    assertThat(multiField.otherFields()).hasSize(1);
    assertThat(multiField.otherFields()[0].suffix()).isEqualTo("keyword");
    assertThat(multiField.otherFields()[0].type()).isEqualTo(FieldType.Keyword);
  }

  @Test
  @DisplayName("验证 content 正文字段配置了 ik_smart_synonym 搜索分词器支持同义词扩展")
  void testContentSearchAnalyzerAnnotation() throws NoSuchFieldException {
    var field = DocChunkDocument.class.getDeclaredField("content");
    org.springframework.data.elasticsearch.annotations.Field fieldAnno =
        field.getAnnotation(org.springframework.data.elasticsearch.annotations.Field.class);
    assertThat(fieldAnno).isNotNull();
    assertThat(fieldAnno.analyzer()).isEqualTo("ik_max_word");
    assertThat(fieldAnno.searchAnalyzer()).isEqualTo("ik_smart_synonym");
  }

  @Test
  @DisplayName("验证 rawScore 字段配置了 @Transient 注解且支持赋值与读取")
  void testRawScoreTransientAnnotation() throws NoSuchFieldException {
    var field = DocChunkDocument.class.getDeclaredField("rawScore");
    org.springframework.data.annotation.Transient transientAnno =
        field.getAnnotation(org.springframework.data.annotation.Transient.class);
    assertThat(transientAnno).isNotNull();

    DocChunkDocument doc = new DocChunkDocument();
    doc.setRawScore(15.5f);
    assertThat(doc.getRawScore()).isEqualTo(15.5f);
  }
}
