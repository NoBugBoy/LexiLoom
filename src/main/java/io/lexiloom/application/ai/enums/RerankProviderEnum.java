package io.lexiloom.application.ai.enums;

import lombok.Getter;

/** 重排模型外部服务供应商枚举 */
@Getter
public enum RerankProviderEnum {
  /** 阿里通义千问 DashScope (如 qwen3.7-text-rerank) */
  DASHSCOPE("阿里通义 DashScope 重排");

  private final String description;

  RerankProviderEnum(String description) {
    this.description = description;
  }
}
