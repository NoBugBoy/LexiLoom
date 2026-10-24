package io.lexiloom.application.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * OpenAPI 密钥启用状态枚举
 */
@Getter
@RequiredArgsConstructor
public enum OpenApiKeyStatusEnum {
  /** 启用中 */
  ENABLED("启用"),

  /** 已禁用 */
  DISABLED("禁用");

  private final String description;
}
