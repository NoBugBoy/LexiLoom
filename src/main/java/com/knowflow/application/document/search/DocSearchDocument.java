package com.knowflow.application.document.search;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.CompletionField;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Setting;
import org.springframework.data.elasticsearch.core.suggest.Completion;

/**
 * Elasticsearch 核心文档检索实体
 *
 * <p>基于 IK 中文分词器建立多级权重全文检索模型，支持高亮、分类过滤、标签匹配、Suggester 自动补全及 Dense Vector 向量预留。
 *
 * <p>索引 Settings 通过 {@code es-settings.json} 注入 IK 中文分词器配置； 若 Elasticsearch 未安装 analysis-ik
 * 插件，启动时会抛出明确的 analyzer not found 错误。 在 docker-compose.yml 的 elasticsearch 服务中已通过插件安装命令引入 IK 插件。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Setting(settingPath = "es-settings.json")
@Document(indexName = "#{@elasticsearchProperties.indexName}")
public class DocSearchDocument {

  /** 对应文档全局唯一主键 (DocumentEntity.id 转 String)，作为 ES _id */
  @Id
  @Field(type = FieldType.Keyword)
  private String id;

  /** 工作区 ID (数据隔离核心字段，所有查询必带 term filter) */
  @Field(type = FieldType.Keyword)
  private String workspaceId;

  /** 文档标题 (检索权重 boost=3，高亮优先) */
  @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
  private String title;

  /** 文档摘要 / 前置描述 (检索权重 boost=2) */
  @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
  private String summary;

  /** Markdown 正文长文本 (检索权重 boost=1) */
  @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
  private String content;

  /** 原文件提取的纯文本 (来自 DocSourceFileEntity.rawText，用于富文件全文检索) */
  @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
  private String rawText;

  /** 标题前缀补全建议字段 (Suggester 自动补全) */
  @CompletionField(maxInputLength = 100)
  private Completion titleSuggest;

  /** 标签列表 (用于标签精确过滤) */
  @Field(type = FieldType.Keyword)
  private List<String> tags;

  /** 分类树节点 ID */
  @Field(type = FieldType.Keyword)
  private String categoryId;

  /** 来源渠道：IMPORT / AI_GENERATED */
  @Field(type = FieldType.Keyword)
  private String sourceType;

  /** 创建人用户 ID */
  @Field(type = FieldType.Keyword)
  private String authorId;

  /** 文档状态 (冗余存储，仅 PUBLISHED 状态写入) */
  @Field(type = FieldType.Keyword)
  private String status;

  /** 发布时间 (用于检索结果按时间排序) */
  @Field(
      type = FieldType.Date,
      format = {DateFormat.date_hour_minute_second, DateFormat.epoch_millis})
  private LocalDateTime publishedAt;

  /** 内容 Dense Vector (维度=1536，阶段四 RAG 向量召回填充，阶段三预留 Mapping) */
  @Field(name = "content_vector", type = FieldType.Dense_Vector, dims = 1536)
  private float[] contentVector;

  /**
   * 辅助方法：快速初始化 titleSuggest
   *
   * @param inputTitle 标题文本
   */
  public void initTitleSuggest(String inputTitle) {
    if (inputTitle != null && !inputTitle.isBlank()) {
      this.titleSuggest = new Completion(new String[] {inputTitle});
    }
  }
}
