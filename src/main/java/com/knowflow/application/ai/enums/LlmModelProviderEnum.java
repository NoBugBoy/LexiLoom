package com.knowflow.application.ai.enums;

import lombok.Getter;

/** 大语言模型供应商枚举 */
@Getter
public enum LlmModelProviderEnum {
  /** 深度求索 (DeepSeek-V3 / DeepSeek-R1) */
  DEEPSEEK("DeepSeek 官方 API"),

  /** 阿里通义千问 (DashScope) */
  QWEN("通义千问 API"),

  /** 本地私有化 Ollama */
  OLLAMA("本地私有化 Ollama"),

  /** 测试与断网降级桩 */
  MOCK("Mock 模拟引擎");

  private final String description;

  LlmModelProviderEnum(String description) {
    this.description = description;
  }
}
