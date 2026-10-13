package com.knowflow.application.user.model.entity;

import com.knowflow.application.common.BaseEntity;
import com.knowflow.application.enums.WorkSpaceAclEnum;
import com.knowflow.application.enums.WorkSpaceStatusEnum;
import com.knowflow.application.exception.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

/** 团队/工作空间聚合根 */
@Entity
@Table(name = "kf_work_space", comment = "团队")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkSpaceEntity extends BaseEntity {

  public static final int DEFAULT_MAX_MEMBERS = 50;

  /** 团队名称 (例如: 核心研发部) */
  @Column(nullable = false, length = 100)
  private String name;

  /** 团队简介 */
  @Column(length = 500)
  private String description;

  /** 团队英文唯一标识/路径别名 (例如: tech-core, 用于 knowflow.io/team/tech-core) */
  @Column(nullable = false, unique = true, length = 50)
  private String code;

  /** 团队 Logo/头像 URL */
  private String avatarUrl;

  /** 团队所有者/创建者用户ID */
  @Column(nullable = false)
  private Long ownerId;

  /** 团队人数上限 (配额防滥用) */
  @Column(nullable = false)
  private Integer maxMembers;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private WorkSpaceStatusEnum status;

  /** 团队公开性 */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private WorkSpaceAclEnum visibility;

  /** 静态工厂：创建新团队 */
  public static WorkSpaceEntity create(
      String name,
      String code,
      String description,
      String avatarUrl,
      Long ownerId,
      Integer maxMembers,
      WorkSpaceAclEnum visibility) {
    if (!StringUtils.hasText(name)) {
      throw BusinessException.badRequest("团队名称不能为空");
    }
    if (!StringUtils.hasText(code)) {
      throw BusinessException.badRequest("团队英文唯一标识(code)不能为空");
    }
    Objects.requireNonNull(ownerId, "团队所有者ID不能为空");

    WorkSpaceEntity entity = new WorkSpaceEntity();
    entity.name = name.trim();
    entity.code = code.trim().toLowerCase();
    entity.description = description;
    entity.avatarUrl = avatarUrl;
    entity.ownerId = ownerId;
    entity.maxMembers = (maxMembers == null || maxMembers <= 0) ? DEFAULT_MAX_MEMBERS : maxMembers;
    entity.status = WorkSpaceStatusEnum.NORMAL;
    entity.visibility = visibility == null ? WorkSpaceAclEnum.PRIVATE : visibility;
    return entity;
  }

  /** 更新基本信息 */
  public void updateInfo(
      String name, String description, String avatarUrl, WorkSpaceAclEnum visibility) {
    assertActive();
    if (StringUtils.hasText(name)) {
      this.name = name.trim();
    }
    this.description = description;
    this.avatarUrl = avatarUrl;
    if (visibility != null) {
      this.visibility = visibility;
    }
  }

  /** 调整人数配额 */
  public void updateMaxMembers(Integer newMaxMembers, long currentMemberCount) {
    assertActive();
    if (newMaxMembers == null || newMaxMembers <= 0) {
      throw BusinessException.badRequest("团队人数上限必须大于0");
    }
    if (newMaxMembers < currentMemberCount) {
      throw BusinessException.badRequest("人数上限不能小于当前已有成员数: " + currentMemberCount);
    }
    this.maxMembers = newMaxMembers;
  }

  /** 校验是否可加入新成员 */
  public void validateCanAddMember(long currentMemberCount) {
    assertActive();
    if (currentMemberCount >= this.maxMembers) {
      throw BusinessException.badRequest("已达到团队人数上限 (" + this.maxMembers + "人)，无法添加更多成员");
    }
  }

  /** 转让团队所有者 */
  public void transferOwnership(Long newOwnerId) {
    assertActive();
    Objects.requireNonNull(newOwnerId, "新所有者ID不能为空");
    if (this.ownerId.equals(newOwnerId)) {
      throw BusinessException.badRequest("新所有者不能为当前所有者");
    }
    this.ownerId = newOwnerId;
  }

  /** 冻结团队 */
  public void freeze() {
    if (this.status == WorkSpaceStatusEnum.DISBANDED) {
      throw BusinessException.badRequest("已解散的团队不可冻结");
    }
    this.status = WorkSpaceStatusEnum.FROZEN;
  }

  /** 激活/恢复团队 */
  public void activate() {
    if (this.status == WorkSpaceStatusEnum.DISBANDED) {
      throw BusinessException.badRequest("已解散的团队无法重新激活");
    }
    this.status = WorkSpaceStatusEnum.NORMAL;
  }

  /** 解散团队 */
  public void disband() {
    if (this.status == WorkSpaceStatusEnum.DISBANDED) {
      throw BusinessException.badRequest("团队已经处于解散状态");
    }
    this.status = WorkSpaceStatusEnum.DISBANDED;
  }

  /** 断言团队处于可用状态 */
  public void assertActive() {
    if (!this.status.isActive()) {
      throw BusinessException.badRequest("团队当前不可用，状态为: " + this.status);
    }
  }

  public boolean isOwner(Long userId) {
    return this.ownerId != null && this.ownerId.equals(userId);
  }
}
