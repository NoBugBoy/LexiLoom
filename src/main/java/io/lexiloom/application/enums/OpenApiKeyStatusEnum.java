package io.lexiloom.application.enums;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * OpenAPI 密钥启用状态枚举
 */
@Getter
@RequiredArgsConstructor
public enum OpenApiKeyStatusEnum {
  /** 启用中 */
  ENABLED("ENABLED", "启用"),

  /** 已禁用 */
  DISABLED("DISABLED", "禁用");

  @JsonValue
  private final String code;
  private final String description;

  /** 是否处于启用状态 */
  public boolean isEnabled() {
    return this == ENABLED;
  }

  /** 是否处于禁用状态 */
  public boolean isDisabled() {
    return this == DISABLED;
  }

  @JsonCreator
  public static @Nullable OpenApiKeyStatusEnum fromValue(@Nullable String value) {
    if (StrUtil.isBlank(value)) {
      return null;
    }
    String clean = value.trim().toUpperCase();
    return switch (clean) {
      case "ENABLED", "启用", "ACTIVE" -> ENABLED;
      case "DISABLED", "禁用", "INACTIVE" -> DISABLED;
      default -> null;
    };
  }

  public static Optional<OpenApiKeyStatusEnum> ofNullable(@Nullable String value) {
    return Optional.ofNullable(fromValue(value));
  }

  public static OpenApiKeyStatusEnum ofOrDefault(@Nullable String value, OpenApiKeyStatusEnum fallback) {
    return ofNullable(value).orElse(fallback);
  }
}
