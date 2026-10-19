package io.lexiloom.application.approval.controller;

import io.lexiloom.application.approval.api.ApprovalApi;
import io.lexiloom.application.approval.api.dto.ApprovalRecordDTO;
import io.lexiloom.application.approval.model.request.ApprovalActionRequest;
import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.enums.ApprovalTypeEnum;
import io.lexiloom.application.exception.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 统一审批管理 REST 控制器 */
@Validated
@RestController
@RequestMapping({"/api/approvals", "/approvals"})
@RequiredArgsConstructor
public class ApprovalController {

  private final ApprovalApi approvalApi;

  /** 查询目标空间下所有待审批单 */
  @GetMapping("/pending")
  public List<ApprovalRecordDTO> listPending(
      @RequestParam("targetWorkspaceId") @NotNull(message = "目标工作空间ID不能为空")
          Long targetWorkspaceId) {
    return approvalApi.listPendingApprovals(targetWorkspaceId);
  }

  /** 查询某业务实体的所有审批流转历史记录 */
  @GetMapping("/history")
  public List<ApprovalRecordDTO> listHistory(
      @RequestParam("type") @NotNull(message = "审批类型不能为空") ApprovalTypeEnum type,
      @RequestParam("bizId") @NotNull(message = "业务主键不能为空") Long bizId) {
    return approvalApi.listApprovalHistory(type, bizId);
  }

  /** 统一处理审批单操作 (通过或驳回) */
  @PostMapping("/action")
  public void handleApprovalAction(@Valid @RequestBody ApprovalActionRequest request) {
    Long approverId = SecurityHolder.getUserId();
    String approverName = SecurityHolder.getUsername().orElse(null);

    if ("APPROVE".equalsIgnoreCase(request.action())) {
      approvalApi.approve(
          request.approvalType(), request.bizId(), approverId, approverName, request.comment());
    } else if ("REJECT".equalsIgnoreCase(request.action())) {
      approvalApi.reject(
          request.approvalType(), request.bizId(), approverId, approverName, request.comment());
    } else {
      throw BusinessException.badRequest("不支持的审批动作: " + request.action());
    }
  }
}
