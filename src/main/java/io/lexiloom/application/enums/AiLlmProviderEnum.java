package io.lexiloom.application.enums;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * 自带模型凭据 (BYOK) 大模型厂商枚举
 */
@Getter
@RequiredArgsConstructor
public enum AiLlmProviderEnum {
  /** 未配置 (沿用平台公共配置) */
  NONE("NONE", "平台默认"),

  /** 通义千问 (DashScope / 百炼) */
  QWEN("QWEN", "通义千问"),

  /** DeepSeek 官方兼容 */
  DEEPSEEK("DEEPSEEK", "DeepSeek");

  @JsonValue
  private final String code;
  private final String description;

  /** 是否已配置自定义外部厂商（非平台默认） */
  public boolean isCustomProvider() {
    return this != NONE;
  }

  @JsonCreator
  public static @Nullable AiLlmProviderEnum fromValue(@Nullable String value) {
    if (StrUtil.isBlank(value)) {
      return null;
    }
    String clean = value.trim().toUpperCase();
    return switch (clean) {
      case "NONE", "平台默认", "DEFAULT" -> NONE;
      case "QWEN", "通义千问", "DASHSCOPE" -> QWEN;
      case "DEEPSEEK" -> DEEPSEEK;
      default -> null;
    };
  }

  public static Optional<AiLlmProviderEnum> ofNullable(@Nullable String value) {
    return Optional.ofNullable(fromValue(value));
  }

  public static AiLlmProviderEnum ofOrDefault(@Nullable String value, AiLlmProviderEnum fallback) {
    return ofNullable(value).orElse(fallback);
  }
}
