package io.lexiloom.application.ai.enums;

import lombok.Getter;

/** 文本向量嵌入供应商枚举 */
@Getter
public enum EmbeddingProviderEnum {
  /** OpenAI 兼容协议接口 (/v1/embeddings) */
  OPENAI_COMPATIBLE("OpenAI 兼容协议"),

  /** 阿里 DashScope 文本向量 */
  DASHSCOPE("阿里通义千问 DashScope 向量服务"),

  /** 本地 Ollama 嵌入服务 */
  OLLAMA("本地私有化 Ollama 嵌入服务"),

  /** 测试与断网降级桩 (基于伪随机归一化向量) */
  MOCK("Mock 模拟向量引擎");

  private final String description;

  EmbeddingProviderEnum(String description) {
    this.description = description;
  }
}
