package com.knowflow.application.document.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocSearchDocument 实体与 IndexInitializer 单元测试")
class DocSearchDocumentAndInitializerTest {

  @Mock private ElasticsearchOperations elasticsearchOperations;
  @Mock private IndexOperations indexOperations;

  private ElasticsearchProperties properties;
  private ElasticsearchIndexInitializer indexInitializer;

  @BeforeEach
  void setUp() {
    properties = new ElasticsearchProperties();
    properties.setIndexName("test_knowflow_index");
    properties.setAutoCreateIndex(true);
    indexInitializer = new ElasticsearchIndexInitializer(elasticsearchOperations, properties);
  }

  @Test
  @DisplayName("DocSearchDocument 实体构建与字段完整性校验")
  void testDocSearchDocumentFieldsAndBuilder() {
    LocalDateTime now = LocalDateTime.now();
    float[] vector = new float[] {0.1f, 0.2f, 0.3f};

    DocSearchDocument doc =
        DocSearchDocument.builder()
            .id("1001")
            .workspaceId("ws_10")
            .title("KnowFlow 架构设计")
            .summary("关于模块化单体与 ES 的架构设计")
            .content("# 核心章节\n这是正文内容")
            .rawText("核心章节 这是正文内容")
            .tags(List.of("架构", "后端"))
            .categoryId("cat_1")
            .sourceType("IMPORT")
            .authorId("user_99")
            .status("PUBLISHED")
            .publishedAt(now)
            .contentVector(vector)
            .build();

    doc.initTitleSuggest("KnowFlow 架构设计");

    assertThat(doc.getId()).isEqualTo("1001");
    assertThat(doc.getWorkspaceId()).isEqualTo("ws_10");
    assertThat(doc.getTitle()).isEqualTo("KnowFlow 架构设计");
    assertThat(doc.getSummary()).isEqualTo("关于模块化单体与 ES 的架构设计");
    assertThat(doc.getContent()).isEqualTo("# 核心章节\n这是正文内容");
    assertThat(doc.getRawText()).isEqualTo("核心章节 这是正文内容");
    assertThat(doc.getTags()).containsExactly("架构", "后端");
    assertThat(doc.getCategoryId()).isEqualTo("cat_1");
    assertThat(doc.getSourceType()).isEqualTo("IMPORT");
    assertThat(doc.getAuthorId()).isEqualTo("user_99");
    assertThat(doc.getStatus()).isEqualTo("PUBLISHED");
    assertThat(doc.getPublishedAt()).isEqualTo(now);
    assertThat(doc.getContentVector()).isEqualTo(vector);

    assertThat(doc.getTitleSuggest()).isNotNull();
    assertThat(doc.getTitleSuggest().getInput()).containsExactly("KnowFlow 架构设计");
  }

  @Test
  @DisplayName("索引不存在时，IndexInitializer 应创建索引并初始化 Mapping")
  void shouldCreateIndexWhenNotExists() {
    when(elasticsearchOperations.indexOps(DocSearchDocument.class)).thenReturn(indexOperations);
    when(indexOperations.exists()).thenReturn(false);
    when(indexOperations.createWithMapping()).thenReturn(true);

    boolean result = indexInitializer.initializeIndex();

    assertThat(result).isTrue();
    verify(indexOperations).createWithMapping();
  }

  @Test
  @DisplayName("索引已存在时，IndexInitializer 应跳过创建")
  void shouldSkipWhenIndexAlreadyExists() {
    when(elasticsearchOperations.indexOps(DocSearchDocument.class)).thenReturn(indexOperations);
    when(indexOperations.exists()).thenReturn(true);

    boolean result = indexInitializer.initializeIndex();

    assertThat(result).isFalse();
    verify(indexOperations, never()).createWithMapping();
  }

  @Test
  @DisplayName("ES 连接或操作异常时，IndexInitializer 应优雅捕获并返回 false")
  void shouldHandleExceptionGracefully() {
    when(elasticsearchOperations.indexOps(DocSearchDocument.class)).thenReturn(indexOperations);
    when(indexOperations.exists()).thenThrow(new RuntimeException("ES cluster unreachable"));

    boolean result = indexInitializer.initializeIndex();

    assertThat(result).isFalse();
    verify(indexOperations, never()).createWithMapping();
  }
}
