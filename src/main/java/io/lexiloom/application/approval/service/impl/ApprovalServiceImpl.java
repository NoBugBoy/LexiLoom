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
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 统一审批对外接口实现 (ApprovalServiceImpl)
 *
 * <p>统筹文档发布审批与工作空间跨域关联授权审批单的创建、防重收敛、状态决断与流转历史查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalServiceImpl implements ApprovalApi {

  private final ApprovalRecordRepository approvalRecordRepository;
  private final ApprovalMapper approvalMapper;
  private final ObjectMapper objectMapper;

  /**
   * 提交空间跨域关联与文档精准授权审批单（自动归档同业务残留的旧待审单）
   *
   * @param spaceLinkId 空间关联主键 ID
   * @param sourceWorkspaceId 发起方工作空间 ID
   * @param targetWorkspaceId 目标审核工作空间 ID
   * @param applicantId 申请人用户 ID
   * @param applicantName 申请人姓名
   * @param applyReason 申请理由
   * @param documentIds 申请授权的文档 ID 列表
   * @return 新生成的审批单主键 ID
   */
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
    supersedeStalePendingRecords(ApprovalTypeEnum.SPACE_LINK, spaceLinkId);

    String extraData = serializeDocumentIds(spaceLinkId, documentIds);
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

  /**
   * 提交文档发布审批单（自动归档同文档残留的旧待审单）
   *
   * @param documentId 文档主键 ID
   * @param workspaceId 所属工作空间 ID
   * @param applicantId 申请人用户 ID
   * @param applicantName 申请人姓名
   * @param applyReason 申请理由
   * @param extraData 扩展 JSON 元数据
   * @return 新生成的审批单主键 ID
   */
  @Override
  @Transactional(rollbackFor = Throwable.class)
  public Long submitDocumentAudit(
      Long documentId,
      Long workspaceId,
      Long applicantId,
      String applicantName,
      String applyReason,
      String extraData) {
    supersedeStalePendingRecords(ApprovalTypeEnum.DOCUMENT_AUDIT, documentId);

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

  /**
   * 执行审批通过（强制要求填写为什么同意的说明）
   *
   * @param type 审批业务类型
   * @param bizId 业务实体主键 ID
   * @param approverId 审批人用户 ID
   * @param approverName 审批人姓名
   * @param approvalComment 审批同意说明
   */
  @Override
  @Transactional(rollbackFor = Throwable.class)
  public void approve(
      ApprovalTypeEnum type,
      Long bizId,
      Long approverId,
      String approverName,
      String approvalComment) {
    ApprovalRecordEntity record = findLatestPendingOrThrow(type, bizId);
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

  /**
   * 执行审批驳回（强制要求填写为什么驳回的说明）
   *
   * @param type 审批业务类型
   * @param bizId 业务实体主键 ID
   * @param approverId 审批人用户 ID
   * @param approverName 审批人姓名
   * @param approvalComment 审批驳回原因
   */
  @Override
  @Transactional(rollbackFor = Throwable.class)
  public void reject(
      ApprovalTypeEnum type,
      Long bizId,
      Long approverId,
      String approverName,
      String approvalComment) {
    ApprovalRecordEntity record = findLatestPendingOrThrow(type, bizId);
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

  /**
   * 申请人主动撤回待审批单
   *
   * @param type 审批业务类型
   * @param bizId 业务实体主键 ID
   * @param operatorId 当前操作人用户 ID
   * @param comment 可选撤回备注说明
   */
  @Override
  @Transactional(rollbackFor = Throwable.class)
  public void cancel(ApprovalTypeEnum type, Long bizId, Long operatorId, String comment) {
    ApprovalRecordEntity record = findLatestPendingOrThrow(type, bizId);
    record.cancel(operatorId, comment);
    approvalRecordRepository.save(record);
    log.info(
        "【Approval】审批单已撤回: recordId={}, type={}, bizId={}, operatorId={}, comment={}",
        record.getId(),
        type,
        bizId,
        operatorId,
        record.getApprovalComment());
  }

  /** 查询最新处于待审批状态的审批单 */
  @Override
  @Transactional(readOnly = true)
  public Optional<ApprovalRecordDTO> getLatestPendingApproval(ApprovalTypeEnum type, Long bizId) {
    if (type == null || bizId == null) {
      return Optional.empty();
    }
    return approvalRecordRepository
        .findFirstByApprovalTypeAndBizIdAndStatusOrderByCreateTimeDesc(
            type, bizId, ApprovalStatusEnum.PENDING)
        .map(approvalMapper::toDTO);
  }

  /** 查询目标工作区下所有待审批单 */
  @Override
  @Transactional(readOnly = true)
  public List<ApprovalRecordDTO> listPendingApprovals(Long targetWorkspaceId) {
    if (targetWorkspaceId == null) {
      return List.of();
    }
    List<ApprovalRecordEntity> list =
        approvalRecordRepository.findByTargetWorkspaceIdAndStatusOrderByCreateTimeDesc(
            targetWorkspaceId, ApprovalStatusEnum.PENDING);
    return approvalMapper.toDTOList(list);
  }

  /** 查询某业务实体所有流转审批记录 */
  @Override
  @Transactional(readOnly = true)
  public List<ApprovalRecordDTO> listApprovalHistory(ApprovalTypeEnum type, Long bizId) {
    if (type == null || bizId == null) {
      return List.of();
    }
    List<ApprovalRecordEntity> list =
        approvalRecordRepository.findAllByApprovalTypeAndBizIdOrderByCreateTimeDesc(type, bizId);
    return approvalMapper.toDTOList(list);
  }

  private ApprovalRecordEntity findLatestPendingOrThrow(ApprovalTypeEnum type, Long bizId) {
    Objects.requireNonNull(type, "审批业务类型不能为空");
    Objects.requireNonNull(bizId, "业务关联主键 bizId 不能为空");
    return approvalRecordRepository
        .findFirstByApprovalTypeAndBizIdAndStatusOrderByCreateTimeDesc(
            type, bizId, ApprovalStatusEnum.PENDING)
        .orElseThrow(
            () -> BusinessException.badRequest("未找到对应的待审批单: type=" + type + ", bizId=" + bizId));
  }

  private void supersedeStalePendingRecords(ApprovalTypeEnum type, Long bizId) {
    if (type == null || bizId == null) {
      return;
    }
    List<ApprovalRecordEntity> staleRecords =
        approvalRecordRepository.findAllByApprovalTypeAndBizIdAndStatus(
            type, bizId, ApprovalStatusEnum.PENDING);
    if (staleRecords == null || staleRecords.isEmpty()) {
      return;
    }
    for (ApprovalRecordEntity stale : staleRecords) {
      stale.supersede("重新提交审批，历史待审单自动归档");
    }
    approvalRecordRepository.saveAll(staleRecords);
  }

  private String serializeDocumentIds(Long spaceLinkId, List<Long> documentIds) {
    try {
      return objectMapper.writeValueAsString(
          Map.of("documentIds", documentIds != null ? documentIds : List.of()));
    } catch (Exception e) {
      log.warn("【Approval】序列化额外参数失败: spaceLinkId={}, error={}", spaceLinkId, e.getMessage());
      return "{\"documentIds\":[]}";
    }
  }
}
