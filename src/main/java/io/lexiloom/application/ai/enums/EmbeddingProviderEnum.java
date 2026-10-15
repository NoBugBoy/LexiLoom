package io.lexiloom.application.ai.enums;

import lombok.Getter;

/** 文本向量嵌入供应商枚举 */
@Getter
public enum EmbeddingProviderEnum {
  /** 阿里 DashScope 文本向量 (通义千问) */
  DASHSCOPE("阿里通义千问 DashScope 向量服务"),

  /** OpenAI 兼容协议接口 (/v1/embeddings) */
  OPENAI_COMPATIBLE("OpenAI 兼容协议");

  private final String description;

  EmbeddingProviderEnum(String description) {
    this.description = description;
  }
}
