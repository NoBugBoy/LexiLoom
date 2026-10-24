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
   * 聚合查询指定团队工作空间的全部文档与知识资产统计指标（供总览大屏使用）
   *
   * @param workSpaceId 工作空间 ID
   * @return 资产与统计 DTO
   */
  WorkspaceDocStatsDTO getWorkspaceDocStats(Long workSpaceId);

  /**
   * 查询目标空间下可被申请关联的文档列表 (仅 PUBLIC 与 INTERNAL，且已审批通过，仅返回名称与概要元数据)
   *
   * @param targetWorkspaceId 目标工作空间 ID
   * @param keyword 可选关键字搜索
   * @return 可申请关联文档传输对象列表
   */
  List<io.lexiloom.application.document.api.dto.LinkableDocumentDTO> listLinkableDocuments(
      Long targetWorkspaceId, String keyword);

  /**
   * 查询源工作空间已审批通过的横向关联目标空间与授权文档 ID 映射 (Key: targetWorkspaceId, Value: documentIds)
   *
   * @param sourceWorkspaceId 源工作空间 ID
   * @return 目标空间与授权文档 ID 列表映射
   */
  java.util.Map<Long, java.util.List<Long>> getApprovedLinkedWorkspaceDocIds(
      Long sourceWorkspaceId);

  /**
   * 查询源工作空间已审批通过的横向关联目标工作空间 ID 列表
   *
   * @param sourceWorkspaceId 源工作空间 ID
   * @return 目标空间 ID 列表
   */
  java.util.List<Long> listApprovedLinkedWorkspaceIds(Long sourceWorkspaceId);

  /**
   * 统计指定工作空间下的文档总数
   *
   * @param workSpaceId 工作空间 ID
   * @return 文档总数
   */
  long countDocumentsByWorkspaceId(Long workSpaceId);

  /**
   * 批量根据分类 ID 查询对应的分类名称映射
   *
   * @param categoryIds 分类 ID 集合
   * @return 分类 ID 与名称映射表
   */
  java.util.Map<Long, String> getCategoryNames(java.util.Collection<Long> categoryIds);
}
