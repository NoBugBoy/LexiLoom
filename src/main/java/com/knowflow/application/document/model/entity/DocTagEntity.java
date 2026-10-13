package com.knowflow.application.document.model.entity;

import cn.hutool.core.util.StrUtil;
import com.knowflow.application.common.BaseEntity;
import com.knowflow.application.exception.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 文档标签实体 */
@Entity
@Table(
    name = "kf_doc_tag",
    comment = "文档标签表",
    indexes = {
      @Index(name = "idx_tag_workspace", columnList = "work_space_id"),
      @Index(name = "uk_workspace_tag_name", unique = true, columnList = "work_space_id, name")
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocTagEntity extends BaseEntity {

  /** 所属团队空间 ID */
  @Column(name = "work_space_id", nullable = false)
  private Long workSpaceId;

  /** 标签名称 */
  @Column(nullable = false, length = 50)
  private String name;

  /** 标签展示颜色 (Hex，如 #1890FF) */
  @Column(length = 20)
  private String color;

  public static DocTagEntity create(Long workSpaceId, String name, String color) {
    Objects.requireNonNull(workSpaceId, "工作区ID不能为空");
    if (StrUtil.isBlank(name)) {
      throw BusinessException.badRequest("标签名称不能为空");
    }

    DocTagEntity entity = new DocTagEntity();
    entity.workSpaceId = workSpaceId;
    entity.name = name.trim();
    entity.color = StrUtil.blankToDefault(color, "#1890FF");
    return entity;
  }

  public void update(String name, String color) {
    if (StrUtil.isNotBlank(name)) {
      this.name = name.trim();
    }
    if (StrUtil.isNotBlank(color)) {
      this.color = color.trim();
    }
  }
}
