package io.lexiloom.application.ai.rag;

import io.lexiloom.application.ai.gateway.rerank.ScoredDocChunkVO;
import io.lexiloom.application.ai.model.search.DocChunkDocument;
import io.lexiloom.application.ai.search.HybridSearchCommand;
import java.util.List;

/**
 * 知识库纯切片检索与重排查询服务 (KnowledgeRetrievalQueryService)
 *
 * <p>基于 CQRS 架构，从 RAG 生成管线中下沉解耦出的无头（Headless）检索服务。
 * 纯语义检索与重排，不调用大模型生成答案，为外部 Agent Skill、CLI 及内部问答编排提供毫秒级、零 LLM Token 损耗的切块召回能力。
 */
public interface KnowledgeRetrievalQueryService {

  /**
   * 纯语义检索与重排，仅返回文档切片实体
   *
   * @param workspaceId 工作空间 ID
   * @param query 自然语言查询
   * @param topK 召回上限 (为 null 时使用系统默认配置)
   * @param minScore 相似度截断阈值 (为 null 时使用系统默认配置)
   * @return 召回切片实体列表
   */
  List<DocChunkDocument> retrieveChunks(
      Long workspaceId, String query, Integer topK, Double minScore);

  /**
   * 纯语义检索与重排，返回携带匹配分数的评分切片候选列表
   *
   * @param workspaceId 工作空间 ID
   * @param query 自然语言查询
   * @param topK 召回上限 (为 null 时使用系统默认配置)
   * @param minScore 相似度截断阈值 (为 null 时使用系统默认配置)
   * @return 携带分数的候选切片列表
   */
  List<ScoredDocChunkVO> retrieveScoredChunks(
      Long workspaceId, String query, Integer topK, Double minScore);

  /**
   * 执行完整的混合检索命令
   *
   * @param command 混合检索命令
   * @return 携带分数的候选切片列表
   */
  List<ScoredDocChunkVO> retrieveScoredChunks(HybridSearchCommand command);
}
