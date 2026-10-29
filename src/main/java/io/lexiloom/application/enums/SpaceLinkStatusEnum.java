package io.lexiloom.application.enums;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * 工作区横向关联（SpaceLink）审批状态枚举
 */
@Getter
@RequiredArgsConstructor
public enum SpaceLinkStatusEnum {
  /** 待审批 */
  PENDING("PENDING", "待审批"),

  /** 审批通过（已生效，可纳入跨空间检索） */
  APPROVED("APPROVED", "已生效"),

  /** 审批驳回/已撤销 */
  REJECTED("REJECTED", "已驳回");

  @JsonValue
  private final String code;
  private final String description;

  /** 是否已审批生效 */
  public boolean isApproved() {
    return this == APPROVED;
  }

  /** 是否处于待审批排队中 */
  public boolean isPending() {
    return this == PENDING;
  }

  /** 是否已被驳回 */
  public boolean isRejected() {
    return this == REJECTED;
  }

  /** 是否处于已完结终态 (生效或驳回) */
  public boolean isTerminated() {
    return this != PENDING;
  }

  @JsonCreator
  public static @Nullable SpaceLinkStatusEnum fromValue(@Nullable String value) {
    if (StrUtil.isBlank(value)) {
      return null;
    }
    String clean = value.trim().toUpperCase();
    return switch (clean) {
      case "PENDING", "待审批", "待审核" -> PENDING;
      case "APPROVED", "已生效", "已同意", "审批通过" -> APPROVED;
      case "REJECTED", "已驳回", "驳回", "已拒绝" -> REJECTED;
      default -> null;
    };
  }

  public static Optional<SpaceLinkStatusEnum> ofNullable(@Nullable String value) {
    return Optional.ofNullable(fromValue(value));
  }

  public static SpaceLinkStatusEnum ofOrDefault(@Nullable String value, SpaceLinkStatusEnum fallback) {
    return ofNullable(value).orElse(fallback);
  }
}
