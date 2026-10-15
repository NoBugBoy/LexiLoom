package io.lexiloom.application.document.search;

import java.util.Optional;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

/**
 * Elasticsearch 文档检索数据访问仓库
 *
 * <p>继承 {@link ElasticsearchRepository}，提供基于 ES 的索引增删改查及自定义按工作区安全隔离查询与删除能力。
 */
@Repository
public interface DocSearchRepository extends ElasticsearchRepository<DocSearchDocument, String> {

  /**
   * 按工作区与文档 ID 安全删除索引
   *
   * @param workspaceId 空间 ID
   * @param id 文档 ID
   */
  void deleteByWorkspaceIdAndId(String workspaceId, String id);

  /**
   * 按工作区与文档 ID 查询索引文档
   *
   * @param workspaceId 空间 ID
   * @param id 文档 ID
   * @return ES 检索文档实体
   */
  Optional<DocSearchDocument> findByWorkspaceIdAndId(String workspaceId, String id);
}
