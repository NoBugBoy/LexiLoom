package io.lexiloom.application.enums;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * 工作空间成员角色枚举
 */
@Getter
@RequiredArgsConstructor
public enum WorkSpaceRoleEnum {
  /** 空间所有者 (Owner)：团队创建人，拥有解散团队、转让团队、计费配置的终极权力 */
  OWNER("OWNER", 100, "空间所有者"),

  /** 空间管理员 (Admin)：负责团队内日常管理（添加/踢除成员、审批文档、管理知识库分类） */
  ADMIN("ADMIN", 50, "空间管理员"),

  /** 协作成​​员 (Member)：日常编写文档的主力（新建文档、编辑、发起审阅、评论） */
  MEMBER("MEMBER", 20, "协作成​​员"),

  /** 访客/只读人员 (Guest/Viewer)：外部合作者或实习生（仅能查看授权文档，不可编辑和创建） */
  GUEST("GUEST", 10, "访客只读");

  @JsonValue
  private final String code;
  private final int level;
  private final String description;

  /** 是否为空间所有者 */
  public boolean isOwner() {
    return this == OWNER;
  }

  /** 是否为空间管理员或以上（管理员/所有者） */
  public boolean isAdminOrAbove() {
    return this.level >= ADMIN.level;
  }

  /** 是否拥有文档协作成​​员及以上权限（可撰写与编辑） */
  public boolean canEdit() {
    return this.level >= MEMBER.level;
  }

  /** 权限等级是否达到目标要求门槛 */
  public boolean isAtLeast(WorkSpaceRoleEnum requiredRole) {
    return requiredRole != null && this.level >= requiredRole.level;
  }

  /** 判断当前角色是否有权管理另一个角色（权限高于目标角色） */
  public boolean canManage(WorkSpaceRoleEnum targetRole) {
    return targetRole != null && this.level > targetRole.level;
  }

  @JsonCreator
  public static @Nullable WorkSpaceRoleEnum fromValue(@Nullable String value) {
    if (StrUtil.isBlank(value)) {
      return null;
    }
    String clean = value.trim().toUpperCase();
    return switch (clean) {
      case "OWNER", "空间所有者", "所有者" -> OWNER;
      case "ADMIN", "空间管理员", "管理员" -> ADMIN;
      case "MEMBER", "协作成员", "协作成​​员", "成员" -> MEMBER;
      case "GUEST", "访客", "只读" -> GUEST;
      default -> null;
    };
  }

  public static Optional<WorkSpaceRoleEnum> ofNullable(@Nullable String value) {
    return Optional.ofNullable(fromValue(value));
  }

  public static WorkSpaceRoleEnum ofOrDefault(@Nullable String value, WorkSpaceRoleEnum fallback) {
    return ofNullable(value).orElse(fallback);
  }
}
