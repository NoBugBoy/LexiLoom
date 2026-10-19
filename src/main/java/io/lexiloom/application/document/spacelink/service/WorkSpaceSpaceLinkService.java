package io.lexiloom.application.document.spacelink.service;

import io.lexiloom.application.document.api.dto.LinkableDocumentDTO;
import io.lexiloom.application.document.spacelink.model.response.AvailableSourcesResponse;
import io.lexiloom.application.document.spacelink.model.response.SpaceLinkResponse;
import java.util.List;
import java.util.Map;

/** 工作区单向横向关联 (SpaceLink) 与可用检索源业务契约 */
public interface WorkSpaceSpaceLinkService {

  /**
   * 发起 SpaceLink 关联申请 (仅源工作区管理员/所有者可发起，需勾选申请关联的具体文档清单)
   *
   * @param sourceWorkspaceId 发起方工作区 ID
   * @param targetWorkspaceId 目标工作区 ID
   * @param documentIds 勾选申请的目标空间文档 ID 列表
   * @param applyReason 申请理由说明
   * @return SpaceLink 主键 ID
   */
  Long apply(
      Long sourceWorkspaceId, Long targetWorkspaceId, List<Long> documentIds, String applyReason);

  /**
   * 发起 SpaceLink 关联申请 (兼容简易调用)
   */
  default Long apply(Long sourceWorkspaceId, Long targetWorkspaceId) {
    return apply(sourceWorkspaceId, targetWorkspaceId, List.of(), null);
  }

  /**
   * 审批通过 SpaceLink 关联 (仅目标工作区管理员/所有者可审批，强制要求填写为什么同意)
   *
   * @param targetWorkspaceId 目标工作区 ID
   * @param linkId 关联记录 ID
   * @param comment 审批通过说明 (为什么同意)
   */
  void approve(Long targetWorkspaceId, Long linkId, String comment);

  /** 审批通过 (默认说明) */
  default void approve(Long targetWorkspaceId, Long linkId) {
    approve(targetWorkspaceId, linkId, "管理员审批通过关联申请");
  }

  /**
   * 驳回或撤销 SpaceLink 关联 (仅目标工作区管理员/所有者可操作，强制要求填写为什么驳回)
   *
   * @param targetWorkspaceId 目标工作区 ID
   * @param linkId 关联记录 ID
   * @param reason 驳回或撤销原因说明 (为什么驳回)
   */
  void reject(Long targetWorkspaceId, Long linkId, String reason);

  /**
   * 查询目标工作区下可供申请关联的文档列表 (仅 PUBLIC 与 INTERNAL，已审批通过，仅包含名称与概要元数据)
   *
   * @param sourceWorkspaceId 发起方工作区 ID (校验操作人权限)
   * @param targetWorkspaceId 目标工作区 ID
   * @param keyword 可选关键字搜索
   * @return 可申请关联文档传输对象列表
   */
  List<LinkableDocumentDTO> listLinkableDocuments(
      Long sourceWorkspaceId, Long targetWorkspaceId, String keyword);

  /**
   * 获取指定工作区已审批通过的全部 SpaceLink 授权文档清单映射
   *
   * @param sourceWorkspaceId 发起方工作区 ID
   * @return 映射：Key 为目标空间 ID，Value 为该目标空间下被授权可供检索的文档 ID 列表
   */
  Map<Long, List<Long>> getApprovedLinkedWorkspaceDocIds(Long sourceWorkspaceId);

  /**
   * 获取指定工作区的可用检索源列表 (含当前空间自身、直系允许聚合的子空间、已审批通过的横向关联)
   *
   * @param workspaceId 当前工作区 ID
   * @return 可用检索源拓扑响应
   */
  AvailableSourcesResponse getAvailableSources(Long workspaceId);

  /** 查询当前工作区对外发起的所有 SpaceLink 列表 */
  List<SpaceLinkResponse> listMyAppliedLinks(Long sourceWorkspaceId);

  /** 查询当前工作区收到的所有待审批 SpaceLink 列表 */
  List<SpaceLinkResponse> listPendingApprovalLinks(Long targetWorkspaceId);
}
