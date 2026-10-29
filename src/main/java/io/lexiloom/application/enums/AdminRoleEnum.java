package io.lexiloom.application.enums;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * 平台管理端角色枚举
 */
@Getter
@RequiredArgsConstructor
public enum AdminRoleEnum {
  /** 超级管理员（仅限一人，系统最高管理权限） */
  SUPER_ADMIN("SUPER_ADMIN", 100, "超级管理员"),

  /** 管理员 */
  ADMIN("ADMIN", 50, "管理员"),

  /** 普通成员 */
  MEMBER("MEMBER", 30, "普通成员"),

  /** 访客 */
  GUEST("GUEST", 10, "访客");

  @JsonValue
  private final String code;
  private final int level;
  private final String description;

  /** 判断当前角色是否有权管理另一个角色（权限比较） */
  public boolean canManage(AdminRoleEnum targetRole) {
    return targetRole != null && this.level > targetRole.level;
  }

  /** 权限等级是否达到目标要求 */
  public boolean isAtLeast(AdminRoleEnum requiredRole) {
    return requiredRole != null && this.level >= requiredRole.level;
  }

  /** 是否为管理员或超级管理员 */
  public boolean isAdminOrAbove() {
    return this.level >= ADMIN.level;
  }

  /** 是否为超级管理员 */
  public boolean isSuperAdmin() {
    return this == SUPER_ADMIN;
  }

  @JsonCreator
  public static @Nullable AdminRoleEnum fromValue(@Nullable String value) {
    if (StrUtil.isBlank(value)) {
      return null;
    }
    String clean = value.trim().toUpperCase();
    return switch (clean) {
      case "SUPER_ADMIN", "超级管理员" -> SUPER_ADMIN;
      case "ADMIN", "管理员" -> ADMIN;
      case "MEMBER", "普通成员", "成员" -> MEMBER;
      case "GUEST", "访客" -> GUEST;
      default -> null;
    };
  }

  public static Optional<AdminRoleEnum> ofNullable(@Nullable String value) {
    return Optional.ofNullable(fromValue(value));
  }

  public static AdminRoleEnum ofOrDefault(@Nullable String value, AdminRoleEnum fallback) {
    return ofNullable(value).orElse(fallback);
  }
}
