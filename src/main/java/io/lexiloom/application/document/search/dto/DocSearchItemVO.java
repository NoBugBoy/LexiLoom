package io.lexiloom.application.document.search.dto;

import cn.hutool.core.date.DatePattern;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 全文检索命中结果条目 VO (不可变 record)
 *
 * @param id 文档全局唯一主键
 * @param title 标题 (含高亮标签)
 * @param summary 摘要 (含高亮标签)
 * @param hitSnippet 高亮匹配上下文片段
 * @param categoryId 分类目录 ID
 * @param tags 文档标签
 * @param sourceType 来源渠道 (IMPORT / AI_GENERATED)
 * @param publishedAt 发布时间
 * @param workspaceId 所属工作空间 ID
 * @param workspaceName 所属工作空间名称
 * @param relationType 关联来源类型 ("PRIMARY" 本空间, "CHILD" 直系子空间, "SPACE_LINK" 横向关联空间)
 */
public record DocSearchItemVO(
    String id,
    String title,
    String summary,
    String hitSnippet,
    String categoryId,
    List<String> tags,
    String sourceType,
    @JsonFormat(pattern = DatePattern.NORM_DATETIME_PATTERN) LocalDateTime publishedAt,
    Long workspaceId,
    String workspaceName,
    String relationType) {

  public DocSearchItemVO {
    tags = tags == null ? Collections.emptyList() : List.copyOf(tags);
  }

  /** 兼容旧版 8 参数构造器 */
  public DocSearchItemVO(
      String id,
      String title,
      String summary,
      String hitSnippet,
      String categoryId,
      List<String> tags,
      String sourceType,
      @JsonFormat(pattern = DatePattern.NORM_DATETIME_PATTERN) LocalDateTime publishedAt) {
    this(
        id,
        title,
        summary,
        hitSnippet,
        categoryId,
        tags,
        sourceType,
        publishedAt,
        null,
        null,
        null);
  }
}
