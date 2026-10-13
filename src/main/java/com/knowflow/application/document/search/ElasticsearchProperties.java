package com.knowflow.application.document.search;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Elasticsearch 检索基础设施配置属性
 *
 * <p>提供索引名称与自动化索引结构初始化的动态配置。 显式声明 Bean 名称以支持 SpEL 表达式 (如 {@code @Document(indexName =
 * "#{@elasticsearchProperties.indexName}")})。
 */
@Getter
@Setter
@Component("elasticsearchProperties")
@ConfigurationProperties(prefix = "knowflow.elasticsearch")
public class ElasticsearchProperties {

  /** ES 核心文档索引名称 */
  private String indexName = "knowflow_doc_index";

  /** 是否自动创建索引和 Mapping */
  private boolean autoCreateIndex = true;
}
