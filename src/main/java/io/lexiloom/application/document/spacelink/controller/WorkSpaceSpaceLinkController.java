package io.lexiloom.application.document.spacelink.controller;

import io.lexiloom.application.document.api.dto.LinkableDocumentDTO;
import io.lexiloom.application.document.spacelink.model.request.SpaceLinkApprovalRequest;
import io.lexiloom.application.document.spacelink.model.request.SpaceLinkCreateRequest;
import io.lexiloom.application.document.spacelink.model.response.AvailableSourcesResponse;
import io.lexiloom.application.document.spacelink.model.response.SpaceLinkResponse;
import io.lexiloom.application.document.spacelink.service.WorkSpaceSpaceLinkService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 工作区单向横向关联 (SpaceLink) API 控制器 */
@RestController
@RequestMapping("/workspace")
@RequiredArgsConstructor
public class WorkSpaceSpaceLinkController {

  private final WorkSpaceSpaceLinkService workSpaceSpaceLinkService;

  /** 发起 SpaceLink 关联申请 (支持选择目标空间具体的 PUBLIC / INTERNAL 文档清单与申请说明) */
  @PostMapping("/{sourceWorkspaceId}/space-links")
  public Long apply(
      @PathVariable("sourceWorkspaceId") Long sourceWorkspaceId,
      @Valid @RequestBody SpaceLinkCreateRequest request) {
    return workSpaceSpaceLinkService.apply(
        sourceWorkspaceId,
        request.targetWorkspaceId(),
        request.documentIds(),
        request.applyReason());
  }

  /** 目标工作区审批 SpaceLink 关联申请 (强制要求填写说明为什么同意或为什么驳回) */
  @PostMapping("/{targetWorkspaceId}/space-links/{linkId}/approval")
  public ResponseEntity<Void> approve(
      @PathVariable("targetWorkspaceId") Long targetWorkspaceId,
      @PathVariable("linkId") Long linkId,
      @Valid @RequestBody SpaceLinkApprovalRequest request) {
    if ("APPROVE".equalsIgnoreCase(request.action())) {
      workSpaceSpaceLinkService.approve(targetWorkspaceId, linkId, request.comment());
    } else {
      workSpaceSpaceLinkService.reject(targetWorkspaceId, linkId, request.comment());
    }
    return ResponseEntity.ok().build();
  }

  /** 查询目标工作区下可供申请关联的文档列表 (仅 PUBLIC 与 INTERNAL，且已审批通过，仅返回名称与元数据) */
  @GetMapping("/{sourceWorkspaceId}/space-links/linkable-documents")
  public List<LinkableDocumentDTO> getLinkableDocuments(
      @PathVariable("sourceWorkspaceId") Long sourceWorkspaceId,
      @RequestParam("targetWorkspaceId") Long targetWorkspaceId,
      @RequestParam(value = "keyword", required = false) String keyword) {
    return workSpaceSpaceLinkService.listLinkableDocuments(
        sourceWorkspaceId, targetWorkspaceId, keyword);
  }

  /** 查询当前工作区对外发起的所有 SpaceLink 关联申请列表 */
  @GetMapping("/{sourceWorkspaceId}/space-links/my-applications")
  public List<SpaceLinkResponse> listMyApplications(
      @PathVariable("sourceWorkspaceId") Long sourceWorkspaceId) {
    return workSpaceSpaceLinkService.listMyAppliedLinks(sourceWorkspaceId);
  }

  /** 目标空间查询收到的所有待审批 SpaceLink 申请列表 */
  @GetMapping("/{targetWorkspaceId}/space-links/pending")
  public List<SpaceLinkResponse> listPendingApprovals(
      @PathVariable("targetWorkspaceId") Long targetWorkspaceId) {
    return workSpaceSpaceLinkService.listPendingApprovalLinks(targetWorkspaceId);
  }

  /** 获取指定工作区的可用检索源拓扑列表 (主空间 + 直系子空间 + 已审批生效的横向关联) */
  @GetMapping("/{workspaceId}/available-sources")
  public AvailableSourcesResponse getAvailableSources(
      @PathVariable("workspaceId") Long workspaceId) {
    return workSpaceSpaceLinkService.getAvailableSources(workspaceId);
  }
}
