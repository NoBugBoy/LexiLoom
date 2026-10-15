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

/** 文档协同评论实体 (支持楼中楼嵌套回复) */
@Entity
@Table(
    name = "ll_doc_comment",
    comment = "文档协同评论表",
    indexes = {
      @Index(name = "idx_comment_document", columnList = "document_id"),
      @Index(name = "idx_comment_parent", columnList = "parent_id"),
      @Index(name = "idx_comment_user", columnList = "user_id")
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocCommentEntity extends BaseEntity {

  /** 所属团队空间 ID */
  @Column(name = "work_space_id", nullable = false)
  private Long workSpaceId;

  /** 关联的主文档 ID */
  @Column(name = "document_id", nullable = false)
  private Long documentId;

  /** 评论发表者用户 ID */
  @Column(name = "user_id", nullable = false)
  private Long userId;

  /** 父评论 ID (0L 为根评论) */
  @Column(name = "parent_id", nullable = false)
  private Long parentId;

  /** 被回复人用户 ID (若是对特定成员的楼中楼回复) */
  @Column(name = "reply_to_user_id")
  private Long replyToUserId;

  /** 评论正文内容 */
  @Column(columnDefinition = "TEXT", nullable = false)
  private String content;

  /** 评论点赞数 */
  @Column(name = "like_count", nullable = false)
  private Integer likeCount;

  /** 静态工厂：创建根评论 */
  public static DocCommentEntity createRoot(
      Long workSpaceId, Long documentId, Long userId, String content) {
    validate(workSpaceId, documentId, userId, content);

    DocCommentEntity entity = new DocCommentEntity();
    entity.workSpaceId = workSpaceId;
    entity.documentId = documentId;
    entity.userId = userId;
    entity.parentId = 0L;
    entity.replyToUserId = null;
    entity.content = content.trim();
    entity.likeCount = 0;
    return entity;
  }

  /** 静态工厂：创建楼中楼回复 */
  public static DocCommentEntity createReply(
      Long workSpaceId,
      Long documentId,
      Long userId,
      Long parentId,
      Long replyToUserId,
      String content) {
    validate(workSpaceId, documentId, userId, content);
    Objects.requireNonNull(parentId, "父评论ID不能为空");

    DocCommentEntity entity = new DocCommentEntity();
    entity.workSpaceId = workSpaceId;
    entity.documentId = documentId;
    entity.userId = userId;
    entity.parentId = parentId;
    entity.replyToUserId = replyToUserId;
    entity.content = content.trim();
    entity.likeCount = 0;
    return entity;
  }

  public void incrementLikeCount() {
    this.likeCount = (this.likeCount == null ? 0 : this.likeCount) + 1;
  }

  private static void validate(Long workSpaceId, Long documentId, Long userId, String content) {
    Objects.requireNonNull(workSpaceId, "工作区ID不能为空");
    Objects.requireNonNull(documentId, "文档ID不能为空");
    Objects.requireNonNull(userId, "用户ID不能为空");
    if (StrUtil.isBlank(content)) {
      throw BusinessException.badRequest("评论内容不能为空");
    }
  }
}
