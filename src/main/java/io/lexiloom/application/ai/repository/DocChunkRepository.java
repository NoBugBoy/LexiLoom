package io.lexiloom.application.ai.repository;

import io.lexiloom.application.ai.model.search.DocChunkDocument;
import java.util.List;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

/**
 * Elasticsearch 文档切块检索仓储接口 (DocChunkRepository)
 *
 * <p>提供针对带空间隔离的切块存储、按文档物理下线删除及顺序列表查询。
 */
@Repository
public interface DocChunkRepository extends ElasticsearchRepository<DocChunkDocument, String> {

  /** 根据工作区与主文档 ID 物理删除所有切块 (文档归档或下线时触发) */
  void deleteByWorkspaceIdAndDocumentId(String workspaceId, String documentId);

  /** 查询某文档的所有原子切块 */
  List<DocChunkDocument> findByWorkspaceIdAndDocumentId(String workspaceId, String documentId);

  /** 按切片物理序号升序查询某文档的所有原子切块 (支持前端切片视图全量渲染) */
  List<DocChunkDocument> findByWorkspaceIdAndDocumentIdOrderByChunkIndexAsc(
      String workspaceId, String documentId);

  /** 统计文档当前已入库的切片总数 */
  long countByWorkspaceIdAndDocumentId(String workspaceId, String documentId);
}
