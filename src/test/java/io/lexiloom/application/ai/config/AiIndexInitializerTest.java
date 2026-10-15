package io.lexiloom.application.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lexiloom.application.ai.model.search.DocChunkDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;

@ExtendWith(MockitoExtension.class)
@DisplayName("AiIndexInitializer 切块索引自动初始化测试")
class AiIndexInitializerTest {

  @Mock private ElasticsearchOperations elasticsearchOperations;
  @Mock private IndexOperations indexOperations;

  private AiProperties properties;
  private AiIndexInitializer initializer;

  @BeforeEach
  void setUp() {
    properties = new AiProperties();
    properties.setChunkIndexName("lexiloom_chunk_index_test");
    initializer = new AiIndexInitializer(elasticsearchOperations, properties);
  }

  @Test
  @DisplayName("索引不存在时，应调用 create 及 putMapping 创建索引并应用 Mapping")
  void shouldCreateIndexWhenNotExists() {
    when(elasticsearchOperations.indexOps(DocChunkDocument.class)).thenReturn(indexOperations);
    when(indexOperations.exists()).thenReturn(false);
    when(indexOperations.create()).thenReturn(true);
    org.springframework.data.elasticsearch.core.document.Document mappingDoc =
        org.springframework.data.elasticsearch.core.document.Document.create();
    java.util.Map<String, Object> props = new java.util.HashMap<>();
    java.util.Map<String, Object> vecProp = new java.util.HashMap<>();
    vecProp.put("dims", 1024);
    props.put("content_vector", vecProp);
    mappingDoc.put("properties", props);

    when(indexOperations.createMapping(DocChunkDocument.class)).thenReturn(mappingDoc);
    when(indexOperations.putMapping(mappingDoc)).thenReturn(true);

    boolean created = initializer.initializeIndex();

    assertThat(created).isTrue();
    verify(indexOperations).create();
    verify(indexOperations).putMapping(mappingDoc);
  }

  @Test
  @DisplayName("索引已存在且维度一致时，应直接跳过创建")
  void shouldSkipWhenIndexExists() {
    when(elasticsearchOperations.indexOps(DocChunkDocument.class)).thenReturn(indexOperations);
    when(indexOperations.exists()).thenReturn(true);
    java.util.Map<String, Object> mapping = new java.util.HashMap<>();
    java.util.Map<String, Object> props = new java.util.HashMap<>();
    java.util.Map<String, Object> vecProp = new java.util.HashMap<>();
    vecProp.put("dims", 1536);
    props.put("content_vector", vecProp);
    mapping.put("properties", props);
    when(indexOperations.getMapping()).thenReturn(mapping);

    // 默认维度在 aiProperties.embedding 中配置为 1536 时
    properties.getEmbedding().setDimension(1536);

    boolean created = initializer.initializeIndex();

    assertThat(created).isFalse();
    verify(indexOperations, never()).create();
  }

  @Test
  @DisplayName("索引已存在但向量维度不匹配时，自动删除旧索引并重新按目标维度创建")
  void shouldRecreateIndexWhenDimensionMismatches() {
    when(elasticsearchOperations.indexOps(DocChunkDocument.class)).thenReturn(indexOperations);
    when(indexOperations.exists()).thenReturn(true);
    java.util.Map<String, Object> mapping = new java.util.HashMap<>();
    java.util.Map<String, Object> props = new java.util.HashMap<>();
    java.util.Map<String, Object> vecProp = new java.util.HashMap<>();
    vecProp.put("dims", 1536); // ES 现有为 1536
    props.put("content_vector", vecProp);
    mapping.put("properties", props);
    when(indexOperations.getMapping()).thenReturn(mapping);

    // 目标维度为 1024
    properties.getEmbedding().setDimension(1024);

    org.springframework.data.elasticsearch.core.document.Document mappingDoc =
        org.springframework.data.elasticsearch.core.document.Document.create();
    java.util.Map<String, Object> newProps = new java.util.HashMap<>();
    java.util.Map<String, Object> newVecProp = new java.util.HashMap<>();
    newVecProp.put("dims", 1536);
    newProps.put("content_vector", newVecProp);
    mappingDoc.put("properties", newProps);

    when(indexOperations.createMapping(DocChunkDocument.class)).thenReturn(mappingDoc);
    when(indexOperations.putMapping(mappingDoc)).thenReturn(true);

    boolean created = initializer.initializeIndex();

    assertThat(created).isTrue();
    // 验证删除了旧索引
    verify(indexOperations).delete();
    // 验证重新创建
    verify(indexOperations).create();
    // 验证 mapping 中的 dims 被动态替换为 1024
    assertThat(newVecProp.get("dims")).isEqualTo(1024);
  }

  @Test
  @DisplayName("发生异常时优雅降级返回 false，不阻断系统启动")
  void shouldHandleExceptionGracefully() {
    when(elasticsearchOperations.indexOps(DocChunkDocument.class))
        .thenThrow(new RuntimeException("ES 连接超时"));

    boolean result = initializer.initializeIndex();

    assertThat(result).isFalse();
  }
}
