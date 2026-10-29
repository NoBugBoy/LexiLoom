package io.lexiloom.application.enums;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * 工作空间与文档访问控制权限级别枚举
 */
@Getter
@RequiredArgsConstructor
public enum WorkSpaceAclEnum {
  /** 公开：全平台或工作空间所有人可读 */
  PUBLIC("PUBLIC", "公开"),

  /** 私有：仅创建者本人及空间管理员可见 */
  PRIVATE("PRIVATE", "私有"),

  /** 内部：空间成员均可读 */
  INTERNAL("INTERNAL", "团队内部");

  @JsonValue
  private final String code;
  private final String description;

  public boolean isPublic() {
    return this == PUBLIC;
  }

  public boolean isPrivate() {
    return this == PRIVATE;
  }

  public boolean isInternal() {
    return this == INTERNAL;
  }

  /**
   * 判断当前级别是否对团队成员默认可见 (公开与内部均可查看)
   */
  public boolean isAccessibleToWorkspaceMembers() {
    return this == PUBLIC || this == INTERNAL;
  }

  @JsonCreator
  public static @Nullable WorkSpaceAclEnum fromValue(@Nullable String value) {
    if (StrUtil.isBlank(value)) {
      return null;
    }
    String clean = value.trim().toUpperCase();
    return switch (clean) {
      case "PUBLIC", "公开" -> PUBLIC;
      case "PRIVATE", "私有" -> PRIVATE;
      case "INTERNAL", "团队内部", "内部" -> INTERNAL;
      default -> null;
    };
  }

  public static Optional<WorkSpaceAclEnum> ofNullable(@Nullable String value) {
    return Optional.ofNullable(fromValue(value));
  }

  public static WorkSpaceAclEnum ofOrDefault(@Nullable String value, WorkSpaceAclEnum fallback) {
    return ofNullable(value).orElse(fallback);
  }
}
