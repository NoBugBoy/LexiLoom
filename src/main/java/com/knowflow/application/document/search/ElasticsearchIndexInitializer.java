package com.knowflow.application.document.search;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Component;

/**
 * Elasticsearch 核心索引自动初始化组件
 *
 * <p>在系统启动时检测文档索引是否存在，若不存在则自动创建并应用 Mapping 配置（包括 IK 中文分词器、多字段 boost 及向量预留）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "knowflow.elasticsearch.auto-create-index",
    havingValue = "true",
    matchIfMissing = true)
public class ElasticsearchIndexInitializer {

  private final ElasticsearchOperations elasticsearchOperations;
  private final ElasticsearchProperties properties;

  @PostConstruct
  public void init() {
    initializeIndex();
  }

  /**
   * 执行索引初始化逻辑
   *
   * @return true 表示成功新建索引并初始化 Mapping，false 表示索引已存在或发生异常
   */
  public boolean initializeIndex() {
    try {
      IndexOperations indexOps = elasticsearchOperations.indexOps(DocSearchDocument.class);
      if (!indexOps.exists()) {
        log.info("Elasticsearch 核心索引 [{}] 不存在，开始自动创建并初始化 Mapping...", properties.getIndexName());
        boolean created = indexOps.createWithMapping();
        log.info("Elasticsearch 核心索引 [{}] 初始化结果: {}", properties.getIndexName(), created);
        return created;
      }
      log.info("Elasticsearch 核心索引 [{}] 已存在，跳过初始化", properties.getIndexName());
      return false;
    } catch (Exception ex) {
      log.error(
          "Elasticsearch 核心索引 [{}] 自动初始化失败: {}", properties.getIndexName(), ex.getMessage(), ex);
      return false;
    }
  }
}
