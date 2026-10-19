package io.lexiloom.application.approval.service.impl;

import io.lexiloom.application.approval.api.ApprovalApi;
import io.lexiloom.application.approval.api.dto.ApprovalRecordDTO;
import io.lexiloom.application.approval.mapper.ApprovalMapper;
import io.lexiloom.application.approval.model.entity.ApprovalRecordEntity;
import io.lexiloom.application.approval.repository.ApprovalRecordRepository;
import io.lexiloom.application.enums.ApprovalStatusEnum;
import io.lexiloom.application.enums.ApprovalTypeEnum;
import io.lexiloom.application.exception.BusinessException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 统一审批对外接口实现 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalServiceImpl implements ApprovalApi {

  private final ApprovalRecordRepository approvalRecordRepository;
  private final ApprovalMapper approvalMapper;
  private final ObjectMapper objectMapper;

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public Long submitSpaceLinkApproval(
      Long spaceLinkId,
      Long sourceWorkspaceId,
      Long targetWorkspaceId,
      Long applicantId,
      String applicantName,
      String applyReason,
      List<Long> documentIds) {
    String extraData = null;
    try {
      extraData =
          objectMapper.writeValueAsString(
              Map.of("documentIds", documentIds != null ? documentIds : List.of()));
    } catch (Exception e) {
      log.warn("【Approval】序列化额外参数失败: spaceLinkId={}, error={}", spaceLinkId, e.getMessage());
      extraData = "{\"documentIds\":[]}";
    }

    ApprovalRecordEntity record =
        ApprovalRecordEntity.createSpaceLinkApproval(
            spaceLinkId,
            sourceWorkspaceId,
            targetWorkspaceId,
            applicantId,
            applicantName,
            applyReason,
            extraData);

    ApprovalRecordEntity saved = approvalRecordRepository.save(record);
    log.info(
        "【Approval】已生成跨空间关联审批单: recordId={}, spaceLinkId={}, sourceWorkspaceId={},"
            + " targetWorkspaceId={}, docCount={}",
        saved.getId(),
        spaceLinkId,
        sourceWorkspaceId,
        targetWorkspaceId,
        documentIds != null ? documentIds.size() : 0);
    return saved.getId();
  }

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public Long submitDocumentAudit(
      Long documentId,
      Long workspaceId,
      Long applicantId,
      String applicantName,
      String applyReason,
      String extraData) {
    ApprovalRecordEntity record =
        ApprovalRecordEntity.createDocumentAudit(
            documentId, workspaceId, applicantId, applicantName, applyReason, extraData);

    ApprovalRecordEntity saved = approvalRecordRepository.save(record);
    log.info(
        "【Approval】已生成文档发布审批单: recordId={}, documentId={}, workspaceId={}, applicantId={}",
        saved.getId(),
        documentId,
        workspaceId,
        applicantId);
    return saved.getId();
  }

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public void approve(
      ApprovalTypeEnum type,
      Long bizId,
      Long approverId,
      String approverName,
      String approvalComment) {
    ApprovalRecordEntity record =
        approvalRecordRepository
            .findFirstByApprovalTypeAndBizIdAndStatusOrderByCreateTimeDesc(
                type, bizId, ApprovalStatusEnum.PENDING)
            .orElseThrow(
                () ->
                    BusinessException.badRequest("未找到对应的待审批单: type=" + type + ", bizId=" + bizId));

    record.approve(approverId, approverName, approvalComment);
    approvalRecordRepository.save(record);
    log.info(
        "【Approval】审批单通过: recordId={}, type={}, bizId={}, approverId={}, comment={}",
        record.getId(),
        type,
        bizId,
        approverId,
        approvalComment);
  }

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public void reject(
      ApprovalTypeEnum type,
      Long bizId,
      Long approverId,
      String approverName,
      String approvalComment) {
    ApprovalRecordEntity record =
        approvalRecordRepository
            .findFirstByApprovalTypeAndBizIdAndStatusOrderByCreateTimeDesc(
                type, bizId, ApprovalStatusEnum.PENDING)
            .orElseThrow(
                () ->
                    BusinessException.badRequest("未找到对应的待审批单: type=" + type + ", bizId=" + bizId));

    record.reject(approverId, approverName, approvalComment);
    approvalRecordRepository.save(record);
    log.info(
        "【Approval】审批单驳回: recordId={}, type={}, bizId={}, approverId={}, comment={}",
        record.getId(),
        type,
        bizId,
        approverId,
        approvalComment);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ApprovalRecordDTO> getLatestPendingApproval(ApprovalTypeEnum type, Long bizId) {
    return approvalRecordRepository
        .findFirstByApprovalTypeAndBizIdAndStatusOrderByCreateTimeDesc(
            type, bizId, ApprovalStatusEnum.PENDING)
        .map(approvalMapper::toDTO);
  }

  @Override
  @Transactional(readOnly = true)
  public List<ApprovalRecordDTO> listPendingApprovals(Long targetWorkspaceId) {
    List<ApprovalRecordEntity> list =
        approvalRecordRepository.findByTargetWorkspaceIdAndStatusOrderByCreateTimeDesc(
            targetWorkspaceId, ApprovalStatusEnum.PENDING);
    return approvalMapper.toDTOList(list);
  }

  @Override
  @Transactional(readOnly = true)
  public List<ApprovalRecordDTO> listApprovalHistory(ApprovalTypeEnum type, Long bizId) {
    List<ApprovalRecordEntity> list =
        approvalRecordRepository.findAllByApprovalTypeAndBizIdOrderByCreateTimeDesc(type, bizId);
    return approvalMapper.toDTOList(list);
  }
}
