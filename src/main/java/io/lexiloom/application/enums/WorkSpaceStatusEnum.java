package io.lexiloom.application.enums;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * 工作空间生命周期状态枚举
 */
@Getter
@RequiredArgsConstructor
public enum WorkSpaceStatusEnum {
  /** 正常状态：团队功能完全正常，允许成员读写、上传与协作 */
  NORMAL("NORMAL", "正常"),

  /** 已冻结 / 禁用：通常由于存储空间超标、违规或管理员手动封禁触发。业务行为：只读模式，不可新建/修改文档，不可上传新文件 */
  FROZEN("FROZEN", "已冻结"),

  /** 已解散：团队已被创建者/管理员正式注销解散。业务行为：彻底不可用，不在列表展示，所有关联文档被封存或转入归档 */
  DISBANDED("DISBANDED", "已解散");

  @JsonValue
  private final String code;
  private final String description;

  /** 辅助业务判断：团队是否可用（处于正常状态） */
  public boolean isActive() {
    return this == NORMAL;
  }

  /** 是否处于冻结状态 */
  public boolean isFrozen() {
    return this == FROZEN;
  }

  /** 是否已被解散注销 */
  public boolean isDisbanded() {
    return this == DISBANDED;
  }

  @JsonCreator
  public static @Nullable WorkSpaceStatusEnum fromValue(@Nullable String value) {
    if (StrUtil.isBlank(value)) {
      return null;
    }
    String clean = value.trim().toUpperCase();
    return switch (clean) {
      case "NORMAL", "正常", "启用" -> NORMAL;
      case "FROZEN", "已冻结", "冻结", "禁用" -> FROZEN;
      case "DISBANDED", "已解散", "解散", "注销" -> DISBANDED;
      default -> null;
    };
  }

  public static Optional<WorkSpaceStatusEnum> ofNullable(@Nullable String value) {
    return Optional.ofNullable(fromValue(value));
  }

  public static WorkSpaceStatusEnum ofOrDefault(@Nullable String value, WorkSpaceStatusEnum fallback) {
    return ofNullable(value).orElse(fallback);
  }
}
