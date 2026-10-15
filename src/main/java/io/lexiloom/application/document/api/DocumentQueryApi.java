package io.lexiloom.application.document.api;

import io.lexiloom.application.document.api.dto.DocSourceFileDTO;
import io.lexiloom.application.document.api.dto.DocumentDTO;
import io.lexiloom.application.document.api.dto.WorkspaceDocStatsDTO;
import java.util.List;
import java.util.Optional;

/**
 * 文档模块跨模块只读查询标准契约 (Spring Modulith 暴露)
 *
 * <p>为 AI / RAG 智能中枢及空间总览等跨模块消费端提供标准只读查询能力，隔离文档内部底层仓储实现。
 */
public interface DocumentQueryApi {

  /**
   * 根据文档 ID 查询文档数据传输对象
   *
   * @param documentId 文档主键 ID
   * @return 存在则返回包含标题与正文的 DocumentDTO
   */
  Optional<DocumentDTO> findDocumentById(Long documentId);

  /**
   * 根据源文件 ID 查询原始源文件资产元数据与解析纯文本
   *
   * @param sourceFileId 源文件主键 ID
   * @return 存在则返回 DocSourceFileDTO
   */
  Optional<DocSourceFileDTO> findSourceFileById(Long sourceFileId);

  /**
   * 根据文档 ID 查询关联的原始源文件资产
   *
   * @param documentId 文档主键 ID
   * @return 存在则返回 DocSourceFileDTO
   */
  Optional<DocSourceFileDTO> findSourceFileByDocumentId(Long documentId);

  /**
   * 查询文档绑定的全部标签名称列表
   *
   * @param documentId 文档主键 ID
   * @return 标签名称不可变列表
   */
  List<String> findTagNamesByDocumentId(Long documentId);

  /**
   * 聚合查询指定团队工作空间的全部文档与知识资产统计指标（供总览大屏使用）
   *
   * @param workSpaceId 工作空间 ID
   * @return 资产与统计 DTO
   */
  WorkspaceDocStatsDTO getWorkspaceDocStats(Long workSpaceId);
}
