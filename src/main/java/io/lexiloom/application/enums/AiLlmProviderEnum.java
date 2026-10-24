package io.lexiloom.application.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 自带模型凭据 (BYOK) 大模型厂商枚举
 */
@Getter
@RequiredArgsConstructor
public enum AiLlmProviderEnum {
  /** 未配置 (沿用平台公共配置) */
  NONE("平台默认"),

  /** 通义千问 (DashScope / 百炼) */
  QWEN("通义千问"),

  /** DeepSeek 官方兼容 */
  DEEPSEEK("DeepSeek");

  private final String description;
}
