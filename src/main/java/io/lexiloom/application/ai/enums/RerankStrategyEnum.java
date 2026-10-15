package io.lexiloom.application.ai.enums;

import lombok.Getter;

/** 重排算法与处理策略枚举 */
@Getter
public enum RerankStrategyEnum {
  /** 不重排 (透传截断，零开销) */
  NONE("不重排 (透传截断)"),

  /** 倒数排名融合算法 (Reciprocal Rank Fusion, 纯数学算法，无外部依赖) */
  RRF("RRF 倒数排名融合算法"),

  /** Cross-Encoder 深度学习模型重排 (通义千问 DashScope qwen3.7-text-rerank 等) */
  CROSS_ENCODER("Cross-Encoder 深度重排模型");

  private final String description;

  RerankStrategyEnum(String description) {
    this.description = description;
  }
}
