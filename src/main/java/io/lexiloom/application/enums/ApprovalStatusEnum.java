package io.lexiloom.application.enums;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/** 审批单状态枚举 */
@Getter
@RequiredArgsConstructor
public enum ApprovalStatusEnum {
  /** 待审批 */
  PENDING("PENDING", "待审批"),

  /** 审批通过已同意 */
  APPROVED("APPROVED", "已同意"),

  /** 审批驳回拒绝 */
  REJECTED("REJECTED", "已驳回"),

  /** 申请人主动撤销 */
  CANCELLED("CANCELLED", "已撤销");

  @JsonValue
  private final String code;
  private final String description;

  /** 是否处于待审批状态 */
  public boolean isPending() {
    return this == PENDING;
  }

  /** 是否审批通过 */
  public boolean isApproved() {
    return this == APPROVED;
  }

  /** 是否审批驳回 */
  public boolean isRejected() {
    return this == REJECTED;
  }

  /** 是否已主动撤销 */
  public boolean isCancelled() {
    return this == CANCELLED;
  }

  /** 是否已结案/处于终态 (已同意、已驳回或已撤销) */
  public boolean isTerminated() {
    return this != PENDING;
  }

  @JsonCreator
  public static @Nullable ApprovalStatusEnum fromValue(@Nullable String value) {
    if (StrUtil.isBlank(value)) {
      return null;
    }
    String clean = value.trim().toUpperCase();
    return switch (clean) {
      case "PENDING", "待审批", "待审核" -> PENDING;
      case "APPROVED", "已同意", "已通过", "审批通过" -> APPROVED;
      case "REJECTED", "已驳回", "驳回", "拒绝" -> REJECTED;
      case "CANCELLED", "已撤销", "撤销" -> CANCELLED;
      default -> null;
    };
  }

  public static Optional<ApprovalStatusEnum> ofNullable(@Nullable String value) {
    return Optional.ofNullable(fromValue(value));
  }

  public static ApprovalStatusEnum ofOrDefault(@Nullable String value, ApprovalStatusEnum fallback) {
    return ofNullable(value).orElse(fallback);
  }
}
