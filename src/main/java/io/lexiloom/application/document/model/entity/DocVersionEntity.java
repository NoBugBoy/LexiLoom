package io.lexiloom.application.document.model.entity;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.common.BaseEntity;
import io.lexiloom.application.exception.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 文档历史版本快照实体 每次正式发布或生成里程碑时固化不可变快照，支持 Myers Diff 差异对比与一键回退 */
@Entity
@org.hibernate.annotations.Immutable
@Table(
    name = "ll_doc_version",
    comment = "文档历史版本快照表",
    indexes = {
      @Index(name = "idx_version_document", columnList = "document_id"),
      @Index(name = "idx_version_workspace", columnList = "work_space_id"),
      @Index(name = "uk_doc_version_num", unique = true, columnList = "document_id, version_number")
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocVersionEntity extends BaseEntity {

  /** 所属团队空间 ID */
  @Column(name = "work_space_id", comment = "所属团队空间ID", nullable = false)
  private Long workSpaceId;

  /** 关联的主文档 ID */
  @Column(name = "document_id", comment = "关联的主文档ID", nullable = false)
  private Long documentId;

  /** 版本序号 (1, 2, 3...) */
  @Column(name = "version_number", comment = "版本序号(1,2,3...)", nullable = false)
  private Integer versionNumber;

  /** 语义化版本标签 (如 v1.0, v1.1) */
  @Column(name = "version_tag", comment = "语义化版本标签(如v1.0)", nullable = false, length = 50)
  private String versionTag;

  /** 快照标题 */
  @Column(comment = "快照标题", nullable = false, length = 200)
  private String title;

  /** 快照 Markdown 正文完整内容 */
  @Column(comment = "快照Markdown正文内容", columnDefinition = "TEXT", nullable = false)
  private String content;

  /** 版本发布说明 / 变更日志 */
  @Column(name = "change_summary", comment = "版本发布说明/变更日志", length = 500)
  private String changeSummary;

  /** 发布人用户 ID */
  @Column(name = "publisher_id", comment = "发布人用户ID", nullable = false)
  private Long publisherId;

  /** 快照字数 */
  @Column(name = "word_count", comment = "快照字数", nullable = false)
  private Integer wordCount;

  /** 静态工厂：创建不可变历史版本快照 */
  public static DocVersionEntity createSnapshot(
      Long workSpaceId,
      Long documentId,
      Integer versionNumber,
      String versionTag,
      String title,
      String content,
      String changeSummary,
      Long publisherId) {
    Objects.requireNonNull(workSpaceId, "工作区ID不能为空");
    Objects.requireNonNull(documentId, "文档ID不能为空");
    Objects.requireNonNull(versionNumber, "版本号不能为空");
    Objects.requireNonNull(publisherId, "发布人ID不能为空");
    if (StrUtil.isBlank(title)) {
      throw BusinessException.badRequest("快照标题不能为空");
    }

    DocVersionEntity entity = new DocVersionEntity();
    entity.workSpaceId = workSpaceId;
    entity.documentId = documentId;
    entity.versionNumber = versionNumber;
    entity.versionTag = StrUtil.blankToDefault(versionTag, "v" + versionNumber + ".0");
    entity.title = title.trim();
    entity.content = content == null ? "" : content;
    entity.changeSummary = changeSummary;
    entity.publisherId = publisherId;
    entity.wordCount = entity.content.length();
    return entity;
  }
}
