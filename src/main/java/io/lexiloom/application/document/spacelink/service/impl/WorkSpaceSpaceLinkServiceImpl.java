package io.lexiloom.application.document.spacelink.service.impl;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.document.api.dto.LinkableDocumentDTO;
import io.lexiloom.application.document.model.entity.DocumentEntity;
import io.lexiloom.application.document.repository.DocumentRepository;
import io.lexiloom.application.document.spacelink.mapper.WorkSpaceSpaceLinkMapper;
import io.lexiloom.application.document.spacelink.model.entity.WorkSpaceSpaceLinkDocEntity;
import io.lexiloom.application.document.spacelink.model.entity.WorkSpaceSpaceLinkEntity;
import io.lexiloom.application.document.spacelink.model.response.AvailableSourcesResponse;
import io.lexiloom.application.document.spacelink.model.response.AvailableSourcesResponse.SpaceLinkSourceItem;
import io.lexiloom.application.document.spacelink.model.response.AvailableSourcesResponse.WorkspaceSourceItem;
import io.lexiloom.application.document.spacelink.model.response.SpaceLinkResponse;
import io.lexiloom.application.document.spacelink.repository.WorkSpaceSpaceLinkDocRepository;
import io.lexiloom.application.document.spacelink.repository.WorkSpaceSpaceLinkRepository;
import io.lexiloom.application.document.spacelink.service.WorkSpaceSpaceLinkService;
import io.lexiloom.application.document.api.dto.DocumentStateEnum;
import io.lexiloom.application.enums.ApprovalTypeEnum;
import io.lexiloom.application.enums.SpaceLinkStatusEnum;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
import io.lexiloom.application.enums.WorkSpaceRoleEnum;
import io.lexiloom.application.enums.WorkSpaceStatusEnum;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.api.WorkSpaceMemberQueryService;
import io.lexiloom.application.user.api.WorkSpaceQueryService;
import io.lexiloom.application.user.api.dto.WorkSpaceDTO;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 工作区单向横向关联 (SpaceLink) 服务实现 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkSpaceSpaceLinkServiceImpl implements WorkSpaceSpaceLinkService {

  private final WorkSpaceQueryService workSpaceQueryService;
  private final WorkSpaceMemberQueryService workSpaceMemberQueryService;
  private final WorkSpaceSpaceLinkRepository workSpaceSpaceLinkRepository;
  private final WorkSpaceSpaceLinkDocRepository workSpaceSpaceLinkDocRepository;
  private final WorkSpaceSpaceLinkMapper workSpaceSpaceLinkMapper;
  private final DocumentRepository documentRepository;
  private final io.lexiloom.application.approval.api.ApprovalApi approvalApi;

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public Long apply(
      Long sourceWorkspaceId, Long targetWorkspaceId, List<Long> documentIds, String applyReason) {
    Long currentUserId = SecurityHolder.getUserId();
    String currentUserName = SecurityHolder.getUsername().orElse(null);
    assertCanManage(sourceWorkspaceId, currentUserId);

    if (Objects.equals(sourceWorkspaceId, targetWorkspaceId)) {
      throw BusinessException.badRequest("不能关联当前工作区自身");
    }

    if (documentIds == null || documentIds.isEmpty()) {
      throw BusinessException.badRequest("请至少勾选一个需要关联的目标空间文档");
    }

    // 校验目标工作空间是否存在且正常
    WorkSpaceDTO targetSpace =
        workSpaceQueryService
            .getById(targetWorkspaceId)
            .orElseThrow(() -> BusinessException.badRequest("目标工作空间不存在: " + targetWorkspaceId));
    if (targetSpace.status() != WorkSpaceStatusEnum.NORMAL) {
      throw BusinessException.badRequest("目标工作空间已被禁用或归档: " + targetWorkspaceId);
    }

    // 校验所选文档是否属于目标空间且处于已发布通过状态
    List<LinkableDocumentDTO> linkableDocs = listLinkableDocumentsInternal(targetWorkspaceId, null);
    Set<Long> validDocIds =
        linkableDocs.stream().map(LinkableDocumentDTO::documentId).collect(Collectors.toSet());

    for (Long docId : documentIds) {
      if (!validDocIds.contains(docId)) {
        throw BusinessException.badRequest("所选文档中包含无效、未审批通过或私有文档: docId=" + docId);
      }
    }

    // 检查是否已有单向关联记录
    Optional<WorkSpaceSpaceLinkEntity> existingOpt =
        workSpaceSpaceLinkRepository.findBySourceWorkspaceIdAndTargetWorkspaceId(
            sourceWorkspaceId, targetWorkspaceId);

    WorkSpaceSpaceLinkEntity linkToSave;
    if (existingOpt.orElse(null) instanceof WorkSpaceSpaceLinkEntity existing) {
      if (existing.getStatus() == SpaceLinkStatusEnum.APPROVED) {
        throw BusinessException.badRequest("与目标空间的关联已审批生效，无需重复申请");
      }
      if (existing.getStatus() == SpaceLinkStatusEnum.PENDING) {
        throw BusinessException.badRequest("关联申请正在审批中，请勿重复申请");
      }
      // REJECTED 状态允许重新申请
      existing.reapply();
      linkToSave = existing;
      // 清理旧文档关联明细
      workSpaceSpaceLinkDocRepository.deleteByLinkId(existing.getId());
    } else {
      linkToSave = WorkSpaceSpaceLinkEntity.create(sourceWorkspaceId, targetWorkspaceId);
    }

    WorkSpaceSpaceLinkEntity savedLink = workSpaceSpaceLinkRepository.save(linkToSave);

    // 持久化关联授权文档明细
    List<WorkSpaceSpaceLinkDocEntity> docEntities =
        documentIds.stream()
            .distinct()
            .map(
                docId ->
                    WorkSpaceSpaceLinkDocEntity.create(savedLink.getId(), targetWorkspaceId, docId))
            .toList();
    workSpaceSpaceLinkDocRepository.saveAll(docEntities);

    // 生成统一审批记录单（类型为 SPACE_LINK，流向目标空间管理员审批）
    approvalApi.submitSpaceLinkApproval(
        savedLink.getId(),
        sourceWorkspaceId,
        targetWorkspaceId,
        currentUserId,
        currentUserName,
        applyReason,
        documentIds);

    log.info(
        "【SpaceLink】已发起关联申请并生成审批单: linkId={}, sourceWorkspaceId={}, targetWorkspaceId={},"
            + " docCount={}",
        savedLink.getId(),
        sourceWorkspaceId,
        targetWorkspaceId,
        docEntities.size());

    return savedLink.getId();
  }

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public void approve(Long targetWorkspaceId, Long linkId, String comment) {
    Long currentUserId = SecurityHolder.getUserId();
    String currentUserName = SecurityHolder.getUsername().orElse(null);
    assertCanManage(targetWorkspaceId, currentUserId);

    if (StrUtil.isBlank(comment)) {
      throw BusinessException.badRequest("审批通过必须填写说明（为什么同意）");
    }

    WorkSpaceSpaceLinkEntity link = findLinkOrThrow(linkId);
    if (!Objects.equals(link.getTargetWorkspaceId(), targetWorkspaceId)) {
      throw BusinessException.forbidden("无权审批其他工作区的关联申请");
    }

    link.approve();
    workSpaceSpaceLinkRepository.save(link);

    // 驱动统一审批单流转至 APPROVED
    approvalApi.approve(
        ApprovalTypeEnum.SPACE_LINK, linkId, currentUserId, currentUserName, comment);

    log.info(
        "【SpaceLink】审批通过: linkId={}, sourceWorkspaceId={}, targetWorkspaceId={}, comment={}",
        link.getId(),
        link.getSourceWorkspaceId(),
        targetWorkspaceId,
        comment);
  }

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public void reject(Long targetWorkspaceId, Long linkId, String reason) {
    Long currentUserId = SecurityHolder.getUserId();
    String currentUserName = SecurityHolder.getUsername().orElse(null);
    assertCanManage(targetWorkspaceId, currentUserId);

    if (StrUtil.isBlank(reason)) {
      throw BusinessException.badRequest("审批驳回必须填写原因（为什么驳回）");
    }

    WorkSpaceSpaceLinkEntity link = findLinkOrThrow(linkId);
    if (!Objects.equals(link.getTargetWorkspaceId(), targetWorkspaceId)) {
      throw BusinessException.forbidden("无权处理其他工作区的关联申请");
    }

    link.reject(reason);
    workSpaceSpaceLinkRepository.save(link);

    // 驱动统一审批单流转至 REJECTED
    approvalApi.reject(ApprovalTypeEnum.SPACE_LINK, linkId, currentUserId, currentUserName, reason);

    log.info(
        "【SpaceLink】审批驳回: linkId={}, sourceWorkspaceId={}, targetWorkspaceId={}, reason={}",
        link.getId(),
        link.getSourceWorkspaceId(),
        targetWorkspaceId,
        reason);
  }

  @Override
  @Transactional(readOnly = true)
  public List<LinkableDocumentDTO> listLinkableDocuments(
      Long sourceWorkspaceId, Long targetWorkspaceId, String keyword) {
    Long currentUserId = SecurityHolder.getUserId();
    assertCanManage(sourceWorkspaceId, currentUserId);

    WorkSpaceDTO targetSpace =
        workSpaceQueryService
            .getById(targetWorkspaceId)
            .orElseThrow(() -> BusinessException.badRequest("目标工作空间不存在: " + targetWorkspaceId));
    if (targetSpace.status() != WorkSpaceStatusEnum.NORMAL) {
      throw BusinessException.badRequest("目标工作空间已被禁用或归档: " + targetWorkspaceId);
    }

    return listLinkableDocumentsInternal(targetWorkspaceId, keyword);
  }

  private List<LinkableDocumentDTO> listLinkableDocumentsInternal(
      Long targetWorkspaceId, String keyword) {
    List<DocumentEntity> docs =
        documentRepository.findLinkableDocuments(
            targetWorkspaceId,
            DocumentStateEnum.APPROVED,
            List.of(WorkSpaceAclEnum.PUBLIC, WorkSpaceAclEnum.INTERNAL),
            StrUtil.blankToDefault(keyword, ""));

    return docs.stream()
        .map(
            d ->
                new LinkableDocumentDTO(
                    d.getId(),
                    d.getTitle(),
                    d.getCategoryId(),
                    d.getVisibility(),
                    d.getUpdateTime()))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Map<Long, List<Long>> getApprovedLinkedWorkspaceDocIds(Long sourceWorkspaceId) {
    if (sourceWorkspaceId == null) {
      return Collections.emptyMap();
    }
    List<WorkSpaceSpaceLinkEntity> approvedLinks =
        workSpaceSpaceLinkRepository.findBySourceWorkspaceIdAndStatus(
            sourceWorkspaceId, SpaceLinkStatusEnum.APPROVED);

    if (approvedLinks.isEmpty()) {
      return Collections.emptyMap();
    }

    List<Long> linkIds = approvedLinks.stream().map(WorkSpaceSpaceLinkEntity::getId).toList();
    List<WorkSpaceSpaceLinkDocEntity> docEntities =
        workSpaceSpaceLinkDocRepository.findByLinkIdIn(linkIds);

    Map<Long, List<Long>> result = new HashMap<>();
    for (WorkSpaceSpaceLinkEntity link : approvedLinks) {
      result.putIfAbsent(link.getTargetWorkspaceId(), new ArrayList<>());
    }
    for (WorkSpaceSpaceLinkDocEntity doc : docEntities) {
      result
          .computeIfAbsent(doc.getTargetWorkspaceId(), k -> new ArrayList<>())
          .add(doc.getDocumentId());
    }
    return result;
  }

  @Override
  @Transactional(readOnly = true)
  public AvailableSourcesResponse getAvailableSources(Long workspaceId) {
    WorkSpaceDTO primary =
        workSpaceQueryService
            .getById(workspaceId)
            .orElseThrow(() -> BusinessException.badRequest("工作空间不存在: " + workspaceId));

    WorkspaceSourceItem primaryItem = new WorkspaceSourceItem(primary.id(), primary.name());

    // 1. 直系子空间 (向下聚合：parentId = workspaceId 且 childAssocEnabled = true)
    List<Long> childIds = workSpaceQueryService.listChildWorkspaceIds(workspaceId);
    Map<Long, String> childNameMap = workSpaceQueryService.getWorkspaceNames(childIds);
    List<WorkspaceSourceItem> childItems =
        childIds.stream()
            .map(id -> new WorkspaceSourceItem(id, childNameMap.getOrDefault(id, "未知空间")))
            .toList();

    // 2. 审批通过的横向关联 (SpaceLink)
    List<WorkSpaceSpaceLinkEntity> approvedLinks =
        workSpaceSpaceLinkRepository.findBySourceWorkspaceIdAndStatus(
            workspaceId, SpaceLinkStatusEnum.APPROVED);

    List<SpaceLinkSourceItem> explicitLinkItems;
    if (approvedLinks.isEmpty()) {
      explicitLinkItems = Collections.emptyList();
    } else {
      List<Long> targetIds =
          approvedLinks.stream().map(WorkSpaceSpaceLinkEntity::getTargetWorkspaceId).toList();
      Map<Long, String> targetNameMap = workSpaceQueryService.getWorkspaceNames(targetIds);

      explicitLinkItems =
          approvedLinks.stream()
              .map(
                  link -> {
                    String name = targetNameMap.getOrDefault(link.getTargetWorkspaceId(), "未知空间");
                    return new SpaceLinkSourceItem(
                        link.getTargetWorkspaceId(), name, link.getStatus().name());
                  })
              .toList();
    }

    return new AvailableSourcesResponse(primaryItem, childItems, explicitLinkItems);
  }

  @Override
  @Transactional(readOnly = true)
  public List<SpaceLinkResponse> listMyAppliedLinks(Long sourceWorkspaceId) {
    List<WorkSpaceSpaceLinkEntity> links =
        workSpaceSpaceLinkRepository.findBySourceWorkspaceId(sourceWorkspaceId);
    return mapToResponses(links);
  }

  @Override
  @Transactional(readOnly = true)
  public List<SpaceLinkResponse> listPendingApprovalLinks(Long targetWorkspaceId) {
    List<WorkSpaceSpaceLinkEntity> links =
        workSpaceSpaceLinkRepository.findByTargetWorkspaceIdAndStatus(
            targetWorkspaceId, SpaceLinkStatusEnum.PENDING);
    return mapToResponses(links);
  }

  private List<SpaceLinkResponse> mapToResponses(List<WorkSpaceSpaceLinkEntity> links) {
    if (links.isEmpty()) {
      return Collections.emptyList();
    }

    List<Long> allSpaceIds = new ArrayList<>();
    List<Long> linkIds = new ArrayList<>();
    for (WorkSpaceSpaceLinkEntity link : links) {
      allSpaceIds.add(link.getSourceWorkspaceId());
      allSpaceIds.add(link.getTargetWorkspaceId());
      linkIds.add(link.getId());
    }

    Map<Long, String> spaceNameMap = workSpaceQueryService.getWorkspaceNames(allSpaceIds);

    List<WorkSpaceSpaceLinkDocEntity> docEntities =
        workSpaceSpaceLinkDocRepository.findByLinkIdIn(linkIds);
    Map<Long, List<Long>> docsByLinkId =
        docEntities.stream()
            .collect(
                Collectors.groupingBy(
                    WorkSpaceSpaceLinkDocEntity::getLinkId,
                    Collectors.mapping(
                        WorkSpaceSpaceLinkDocEntity::getDocumentId, Collectors.toList())));

    return links.stream()
        .map(
            link ->
                workSpaceSpaceLinkMapper.toResponse(
                    link,
                    spaceNameMap.getOrDefault(link.getSourceWorkspaceId(), "未知空间"),
                    spaceNameMap.getOrDefault(link.getTargetWorkspaceId(), "未知空间"),
                    docsByLinkId.getOrDefault(link.getId(), Collections.emptyList())))
        .toList();
  }

  private WorkSpaceSpaceLinkEntity findLinkOrThrow(Long linkId) {
    return workSpaceSpaceLinkRepository
        .findById(linkId)
        .orElseThrow(() -> BusinessException.badRequest("关联记录不存在: " + linkId));
  }

  private void assertCanManage(Long workspaceId, Long userId) {
    if (userId == null) {
      throw BusinessException.unauthorized("未登录或登录已过期");
    }
    if (!workSpaceMemberQueryService.hasRole(workspaceId, userId, WorkSpaceRoleEnum.ADMIN)) {
      throw BusinessException.forbidden("仅团队所有者或管理员有权执行此操作");
    }
  }
}
