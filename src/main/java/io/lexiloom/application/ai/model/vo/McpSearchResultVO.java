package io.lexiloom.application.ai.model.vo;

import java.util.List;

/**
 * MCP/Skill 知识库纯切片检索结果视图对象 (不可变 record)
 *
 * <p>封装自然语言检索命中的切片候选列表及元数据，不调用大模型生成答案。
 */
public record McpSearchResultVO(
    String query,
    Long workspaceId,
    int totalMatches,
    List<McpChunkResultVO> results,
    String traceId) {

  public McpSearchResultVO(
      String query, Long workspaceId, int totalMatches, List<McpChunkResultVO> results) {
    this(query, workspaceId, totalMatches, results, null);
  }

  public McpSearchResultVO {
    results = results != null ? List.copyOf(results) : List.of();
  }
}
