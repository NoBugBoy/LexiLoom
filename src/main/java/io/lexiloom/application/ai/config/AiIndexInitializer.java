package io.lexiloom.application.ai.config;

import io.lexiloom.application.ai.model.search.DocChunkDocument;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Component;

/**
 * AI 向量切块索引自动初始化组件 (AiIndexInitializer)
 *
 * <p>在系统启动时检测 {@code lexiloom_chunk_index} 切块索引是否存在，若不存在则自动创建并应用 Mapping 配置 （包含 Dense Vector
 * 向量映射、IK 中文分词器、多字段 boost 及物理原件坐标 bbox）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "lexiloom.ai.auto-create-index",
    havingValue = "true",
    matchIfMissing = true)
public class AiIndexInitializer {

  private final ElasticsearchOperations elasticsearchOperations;
  private final AiProperties aiProperties;

  @PostConstruct
  public void init() {
    initializeIndex();
  }

  /**
   * 执行切块索引初始化逻辑
   *
   * @return true 表示成功新建或自愈重建索引并初始化 Mapping，false 表示索引已存在且维度一致或发生异常
   */
  public boolean initializeIndex() {
    String indexName = aiProperties.getChunkIndexName();
    int targetDimension =
        aiProperties.getEmbedding() != null && aiProperties.getEmbedding().getDimension() > 0
            ? aiProperties.getEmbedding().getDimension()
            : 1024;

    try {
      IndexOperations indexOps = elasticsearchOperations.indexOps(DocChunkDocument.class);
      if (indexOps.exists()) {
        // 检测现有 Mapping 中的向量维度是否与目标维度匹配
        Integer existingDims = extractExistingVectorDimension(indexOps);
        if (existingDims != null && existingDims != targetDimension) {
          log.warn(
              "【AiIndexInitializer】检测到切块索引 [{}] 向量维度不匹配 (ES现有={}, 目标={})，自动删除重建索引...",
              indexName,
              existingDims,
              targetDimension);
          indexOps.delete();
        } else {
          log.info(
              "【AiIndexInitializer】ES 向量切块索引 [{}] 已存在且维度一致 ({})，跳过初始化",
              indexName,
              existingDims != null ? existingDims : targetDimension);
          return false;
        }
      }

      log.info(
          "【AiIndexInitializer】ES 向量切块索引 [{}] 开始创建并初始化 Mapping (维度={})...",
          indexName,
          targetDimension);
      indexOps.create();
      org.springframework.data.elasticsearch.core.document.Document mappingDoc =
          indexOps.createMapping(DocChunkDocument.class);

      applyTargetDimensionToMapping(mappingDoc, targetDimension);

      boolean mapped = indexOps.putMapping(mappingDoc);
      log.info("【AiIndexInitializer】ES 向量切块索引 [{}] 初始化结果: mappingApplied={}", indexName, mapped);
      return true;
    } catch (Exception ex) {
      log.error("【AiIndexInitializer】ES 向量切块索引 [{}] 自动初始化失败: {}", indexName, ex.getMessage(), ex);
      return false;
    }
  }

  private Integer extractExistingVectorDimension(IndexOperations indexOps) {
    try {
      java.util.Map<String, Object> mapping = indexOps.getMapping();
      if (mapping == null) {
        return null;
      }
      Object propsObj = mapping.get("properties");
      if (propsObj instanceof java.util.Map<?, ?> propsMap) {
        Object vecObj = propsMap.get("content_vector");
        if (vecObj instanceof java.util.Map<?, ?> vecMap) {
          Object dimsObj = vecMap.get("dims");
          if (dimsObj instanceof Number n) {
            return n.intValue();
          }
        }
      }
    } catch (Exception e) {
      log.warn("【AiIndexInitializer】读取现有索引 Mapping 异常: {}", e.getMessage());
    }
    return null;
  }

  private void applyTargetDimensionToMapping(
      org.springframework.data.elasticsearch.core.document.Document mappingDoc,
      int targetDimension) {
    Object propsObj = mappingDoc.get("properties");
    if (propsObj instanceof java.util.Map<?, ?> propsMap) {
      @SuppressWarnings("unchecked")
      java.util.Map<String, Object> castProps = (java.util.Map<String, Object>) propsMap;
      Object vecObj = castProps.get("content_vector");
      if (vecObj instanceof java.util.Map<?, ?> vecMap) {
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> castVec = (java.util.Map<String, Object>) vecMap;
        castVec.put("dims", targetDimension);
      }
    }
  }
}
