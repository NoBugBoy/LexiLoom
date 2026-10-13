package com.knowflow.application.ai.enums;

import lombok.Getter;

/** 重排模型外部服务供应商枚举 */
@Getter
public enum RerankProviderEnum {
  /** 阿里通义千问 DashScope (如 gte-rerank) */
  DASHSCOPE("阿里通义 DashScope 重排"),

  /** Jina AI Reranker */
  JINA("Jina AI 重排服务"),

  /** Cohere Rerank API */
  COHERE("Cohere 重排服务"),

  /** 硅基流动 SiliconFlow */
  SILICONFLOW("硅基流动重排服务"),

  /** 本地私有化 Ollama / TEI */
  OLLAMA("本地私有化 Ollama / TEI 重排"),

  /** Mock 本地测试供应商 */
  MOCK("Mock本地测试供应商");

  private final String description;

  RerankProviderEnum(String description) {
    this.description = description;
  }
}
