package com.knowflow.application.ai.gateway.rerank;

import com.knowflow.application.ai.enums.RerankStrategyEnum;
import java.util.List;

/** 候选切块重排防腐层网关契约 (RerankGateway，完全可选与可插拔设计) */
public interface RerankGateway {

  /**
   * 对初始召回的候选切块执行重排打分
   *
   * @param command 重排命令（包含用户提问与候选切块集）
   * @return 重排打分后的 Top-K 切片列表 (按相关度降序排列)
   */
  List<ScoredDocChunkVO> rerank(RerankCommand command);

  /** 当前重排策略是否生效开启 */
  boolean isEnabled();

  /** 获取策略枚举类型 */
  RerankStrategyEnum getStrategy();
}
