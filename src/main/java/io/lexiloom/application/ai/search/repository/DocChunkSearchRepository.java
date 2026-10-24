package io.lexiloom.application.ai.search.repository;

import io.lexiloom.application.ai.model.search.DocChunkDocument;
import io.lexiloom.application.ai.search.HybridSearchCommand;
import io.lexiloom.application.ai.search.domain.LexicalQuery;
import java.util.List;

/**
 * 文档切块检索引擎仓储契约 (防腐层)
 *
 * <p>隔离底层 Elasticsearch 8.x Client 与 NativeQuery DSL，为领域检索策略提供强类型的切片召回服务。
 */
public interface DocChunkSearchRepository {

  /** 执行 BM25 词法全文检索 */
  List<DocChunkDocument> searchLexical(
      HybridSearchCommand command,
      LexicalQuery lexicalQuery,
      Long workspaceId,
      boolean onlyPublic,
      List<Long> allowedDocIds,
      int limit);

  /** 执行 kNN 向量相似度检索 */
  List<DocChunkDocument> searchVector(
      HybridSearchCommand command,
      float[] queryVector,
      Long workspaceId,
      boolean onlyPublic,
      List<Long> allowedDocIds,
      int limit);

  /** 执行 ES 原生单请求双路复合检索 (Single-Request Native Hybrid Search) */
  List<DocChunkDocument> searchNativeHybrid(
      HybridSearchCommand command,
      LexicalQuery lexicalQuery,
      float[] queryVector,
      Long workspaceId,
      boolean onlyPublic,
      List<Long> allowedDocIds,
      int limit);
}
