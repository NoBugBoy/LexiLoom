package com.knowflow.application.ai.model.search;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Setting;

/**
 * Elasticsearch 文档原子切块检索实体 (DocChunkDocument)
 *
 * <p>基于 DeepDoc 视觉版面与几何拓扑聚类提取的物理原子段落存储，彻底摒弃破坏语义边界的滑动窗口与 Overlap。 包含上下文帽子 (Context Prefix)、Dense
 * Vector (1536 维) 及物理原件页码/坐标 (page, bbox)，支撑前端像素级高保真划线高亮。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Setting(settingPath = "es-settings.json")
@Document(indexName = "#{@aiProperties.chunkIndexName}")
public class DocChunkDocument {

  /** 切块唯一标识 (格式: {documentId}_{chunkIndex})，作为 ES _id */
  @Id
  @Field(type = FieldType.Keyword)
  private String id;

  /** 工作区 ID (多租户数据隔离核心字段，所有查询必带 term filter) */
  @Field(type = FieldType.Keyword)
  private String workspaceId;

  /** 归属主文档 ID (DocumentEntity.id) */
  @Field(type = FieldType.Keyword)
  private String documentId;

  /** 原子切片在文档内的绝对序号 (0, 1, 2...) */
  @Field(type = FieldType.Integer)
  private Integer chunkIndex;

  /** DeepDoc 解析器返回的原生切片 ID (如 chunk_p_0, docx_p_1) */
  @Field(type = FieldType.Keyword)
  private String chunkId;

  /** 归属主文档标题 (用于检索加权与召回展示) */
  @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
  private String title;

  /** 章节层级面包屑导航路径 (如 "技术架构 / 存储设计 / 向量索引") */
  @Field(type = FieldType.Keyword)
  private String headerPath;

  /**
   * 原子段落纯净原文
   *
   * <p>严格保持 DeepDoc YOLO + KMeans 识别的天然物理边界，不含任何滑动窗口拼接与重叠文本。
   */
  @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
  private String content;

  /**
   * 上下文帽子前缀文本
   *
   * <p>格式: {@code [文档: 《{title}》] > [章节: {headerPath}] > [类型: {chunkType}]}
   */
  @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
  private String contextPrefix;

  /**
   * 语义密集向量 (Dense Vector，默认维度=1024 对齐通义千问模型，Cosine 相似度)
   *
   * <p>基于带帽子的上下文富化文本 (contextPrefix + content) 由 Spring AI EmbeddingModel 驱动计算。
   */
  @Field(name = "content_vector", type = FieldType.Dense_Vector, dims = 1024)
  private float[] contentVector;

  /** PDF / 原件所在自然页码 (1-indexed，用于前端阅读器翻页跳转) */
  @Field(type = FieldType.Integer)
  private Integer page;

  /**
   * 原件视觉矩形边界框坐标 [x0, y0, x1, y1]
   *
   * <p>对应左下/右上或归一化绝对物理坐标，用于前端 PDF.js / Canvas 像素级高亮画框。
   */
  @Field(type = FieldType.Float)
  private List<Float> bbox;

  /** 切片版面元素类型 (text / table / slide / figure 等) */
  @Field(type = FieldType.Keyword)
  private String chunkType;

  /** 结构化定位锚点 (如 "段落_1", "表格_2") */
  @Field(type = FieldType.Keyword)
  private String anchor;

  /** 标签列表 (用于标签精确过滤) */
  @Field(type = FieldType.Keyword)
  private List<String> tags;

  /** 文档分类 ID */
  @Field(type = FieldType.Keyword)
  private String categoryId;

  /** 更新入库时间 */
  @Field(
      type = FieldType.Date,
      format = {DateFormat.date_hour_minute_second, DateFormat.epoch_millis})
  private LocalDateTime updateTime;

  /** 生成标准切块主键 */
  public static String generateId(Object documentId, Integer chunkIndex) {
    return documentId + "_" + (chunkIndex != null ? chunkIndex : 0);
  }
}
