package com.knowflow.application.document.model.entity;

import com.knowflow.application.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 文档与标签关联实体 */
@Entity
@Table(
    name = "kf_doc_tag_relation",
    comment = "文档与标签关联表",
    indexes = {
      @Index(name = "idx_rel_document", columnList = "document_id"),
      @Index(name = "idx_rel_tag", columnList = "tag_id"),
      @Index(name = "uk_doc_tag", unique = true, columnList = "document_id, tag_id")
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocTagRelationEntity extends BaseEntity {

  @Column(name = "document_id", nullable = false)
  private Long documentId;

  @Column(name = "tag_id", nullable = false)
  private Long tagId;

  public static DocTagRelationEntity create(Long documentId, Long tagId) {
    Objects.requireNonNull(documentId, "文档ID不能为空");
    Objects.requireNonNull(tagId, "标签ID不能为空");

    DocTagRelationEntity entity = new DocTagRelationEntity();
    entity.documentId = documentId;
    entity.tagId = tagId;
    return entity;
  }
}
