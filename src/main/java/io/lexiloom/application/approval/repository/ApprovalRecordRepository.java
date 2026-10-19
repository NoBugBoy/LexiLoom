package io.lexiloom.application.approval.repository;

import io.lexiloom.application.approval.model.entity.ApprovalRecordEntity;
import io.lexiloom.application.enums.ApprovalStatusEnum;
import io.lexiloom.application.enums.ApprovalTypeEnum;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** 统一审批记录仓储 */
@Repository
public interface ApprovalRecordRepository extends JpaRepository<ApprovalRecordEntity, Long> {

  /** 根据目标工作空间与审批状态查询审批单列表 (用于待我审批列表) */
  List<ApprovalRecordEntity> findByTargetWorkspaceIdAndStatusOrderByCreateTimeDesc(
      Long targetWorkspaceId, ApprovalStatusEnum status);

  /** 根据目标工作空间分页查询审批单 */
  Page<ApprovalRecordEntity> findByTargetWorkspaceIdOrderByCreateTimeDesc(
      Long targetWorkspaceId, Pageable pageable);

  /** 查询某业务实体最新一条指定状态的审批单 */
  Optional<ApprovalRecordEntity> findFirstByApprovalTypeAndBizIdAndStatusOrderByCreateTimeDesc(
      ApprovalTypeEnum approvalType, Long bizId, ApprovalStatusEnum status);

  /** 查询某业务实体关联的所有审批流转记录（按时间倒序） */
  List<ApprovalRecordEntity> findAllByApprovalTypeAndBizIdOrderByCreateTimeDesc(
      ApprovalTypeEnum approvalType, Long bizId);
}
