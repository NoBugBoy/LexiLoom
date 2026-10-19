package io.lexiloom.application.approval.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lexiloom.application.approval.api.dto.ApprovalRecordDTO;
import io.lexiloom.application.approval.mapper.ApprovalMapper;
import io.lexiloom.application.approval.model.entity.ApprovalRecordEntity;
import io.lexiloom.application.approval.repository.ApprovalRecordRepository;
import io.lexiloom.application.approval.service.impl.ApprovalServiceImpl;
import io.lexiloom.application.enums.ApprovalStatusEnum;
import io.lexiloom.application.enums.ApprovalTypeEnum;
import io.lexiloom.application.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("统一审批流服务 (ApprovalService) 业务测试")
class ApprovalServiceTest {

  @Mock private ApprovalRecordRepository approvalRecordRepository;
  @Mock private ApprovalMapper approvalMapper;
  @Spy private ObjectMapper objectMapper = new ObjectMapper();

  @InjectMocks private ApprovalServiceImpl approvalService;

  private ApprovalRecordEntity spaceLinkRecord;
  private ApprovalRecordEntity docAuditRecord;

  @BeforeEach
  void setUp() {
    spaceLinkRecord =
        ApprovalRecordEntity.createSpaceLinkApproval(
            1001L,
            1L,
            2L,
            999L,
            "张三",
            "申请跨部门文档联合检索",
            "{\"documentIds\":[10,20]}");
    ReflectionTestUtils.setField(spaceLinkRecord, "id", 5001L);

    docAuditRecord =
        ApprovalRecordEntity.createDocumentAudit(
            2001L,
            1L,
            999L,
            "张三",
            "发布v1.0技术白皮书",
            null);
    ReflectionTestUtils.setField(docAuditRecord, "id", 5002L);
  }

  @Test
  @DisplayName("提交空间关联审批单：正常创建并落库")
  void testSubmitSpaceLinkApproval() {
    when(approvalRecordRepository.save(any(ApprovalRecordEntity.class)))
        .thenAnswer(inv -> {
          ApprovalRecordEntity r = inv.getArgument(0);
          ReflectionTestUtils.setField(r, "id", 5003L);
          return r;
        });

    Long id =
        approvalService.submitSpaceLinkApproval(
            1001L, 1L, 2L, 999L, "张三", "申请跨部门关联", List.of(10L, 20L));

    assertThat(id).isEqualTo(5003L);
    verify(approvalRecordRepository).save(any(ApprovalRecordEntity.class));
  }

  @Test
  @DisplayName("提交文档发布审核单：正常创建并落库")
  void testSubmitDocumentAudit() {
    when(approvalRecordRepository.save(any(ApprovalRecordEntity.class)))
        .thenAnswer(inv -> {
          ApprovalRecordEntity r = inv.getArgument(0);
          ReflectionTestUtils.setField(r, "id", 5004L);
          return r;
        });

    Long id =
        approvalService.submitDocumentAudit(
            2001L, 1L, 999L, "张三", "文档审批发布", null);

    assertThat(id).isEqualTo(5004L);
    verify(approvalRecordRepository).save(any(ApprovalRecordEntity.class));
  }

  @Test
  @DisplayName("审批通过：成功流转并强制记录为什么同意的说明")
  void testApproveSuccess() {
    when(approvalRecordRepository.findFirstByApprovalTypeAndBizIdAndStatusOrderByCreateTimeDesc(
            ApprovalTypeEnum.SPACE_LINK, 1001L, ApprovalStatusEnum.PENDING))
        .thenReturn(Optional.of(spaceLinkRecord));

    approvalService.approve(
        ApprovalTypeEnum.SPACE_LINK, 1001L, 888L, "李四管理员", "经审查符合公司信息共享合规要求，同意关联");

    assertThat(spaceLinkRecord.getStatus()).isEqualTo(ApprovalStatusEnum.APPROVED);
    assertThat(spaceLinkRecord.getApproverId()).isEqualTo(888L);
    assertThat(spaceLinkRecord.getApproverName()).isEqualTo("李四管理员");
    assertThat(spaceLinkRecord.getApprovalComment()).isEqualTo("经审查符合公司信息共享合规要求，同意关联");
    assertThat(spaceLinkRecord.getApprovalTime()).isNotNull();
    verify(approvalRecordRepository).save(spaceLinkRecord);
  }

  @Test
  @DisplayName("审批通过：说明为空时抛出业务异常 (拒绝无理由通过)")
  void testApproveBlankCommentThrows() {
    when(approvalRecordRepository.findFirstByApprovalTypeAndBizIdAndStatusOrderByCreateTimeDesc(
            ApprovalTypeEnum.SPACE_LINK, 1001L, ApprovalStatusEnum.PENDING))
        .thenReturn(Optional.of(spaceLinkRecord));

    assertThatThrownBy(
            () ->
                approvalService.approve(
                    ApprovalTypeEnum.SPACE_LINK, 1001L, 888L, "李四管理员", "  "))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("审批通过必须填写说明");
  }

  @Test
  @DisplayName("审批驳回：成功流转并强制记录为什么驳回的原因")
  void testRejectSuccess() {
    when(approvalRecordRepository.findFirstByApprovalTypeAndBizIdAndStatusOrderByCreateTimeDesc(
            ApprovalTypeEnum.SPACE_LINK, 1001L, ApprovalStatusEnum.PENDING))
        .thenReturn(Optional.of(spaceLinkRecord));

    approvalService.reject(
        ApprovalTypeEnum.SPACE_LINK, 1001L, 888L, "李四管理员", "包含未脱敏内部财务字段，拒绝关联");

    assertThat(spaceLinkRecord.getStatus()).isEqualTo(ApprovalStatusEnum.REJECTED);
    assertThat(spaceLinkRecord.getApproverId()).isEqualTo(888L);
    assertThat(spaceLinkRecord.getApproverName()).isEqualTo("李四管理员");
    assertThat(spaceLinkRecord.getApprovalComment()).isEqualTo("包含未脱敏内部财务字段，拒绝关联");
    assertThat(spaceLinkRecord.getApprovalTime()).isNotNull();
    verify(approvalRecordRepository).save(spaceLinkRecord);
  }

  @Test
  @DisplayName("审批驳回：原因未填写时抛出业务异常")
  void testRejectBlankCommentThrows() {
    when(approvalRecordRepository.findFirstByApprovalTypeAndBizIdAndStatusOrderByCreateTimeDesc(
            ApprovalTypeEnum.SPACE_LINK, 1001L, ApprovalStatusEnum.PENDING))
        .thenReturn(Optional.of(spaceLinkRecord));

    assertThatThrownBy(
            () ->
                approvalService.reject(
                    ApprovalTypeEnum.SPACE_LINK, 1001L, 888L, "李四管理员", ""))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("审批驳回必须填写原因");
  }

  @Test
  @DisplayName("重复审批或找不到待审批单时抛出异常")
  void testApprovalNotFoundThrows() {
    when(approvalRecordRepository.findFirstByApprovalTypeAndBizIdAndStatusOrderByCreateTimeDesc(
            ApprovalTypeEnum.SPACE_LINK, 9999L, ApprovalStatusEnum.PENDING))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                approvalService.approve(
                    ApprovalTypeEnum.SPACE_LINK, 9999L, 888L, "李四", "同意"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("未找到对应的待审批单");
  }

  @Test
  @DisplayName("实体级撤回：申请人可撤回待审批单，非申请人越权撤回抛出异常")
  void testCancelApproval() {
    // 申请人本人撤回
    spaceLinkRecord.cancel(999L);
    assertThat(spaceLinkRecord.getStatus()).isEqualTo(ApprovalStatusEnum.CANCELLED);

    // 已取消再次撤回
    assertThatThrownBy(() -> spaceLinkRecord.cancel(999L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("无法撤回");

    // 非本人撤回
    assertThatThrownBy(() -> docAuditRecord.cancel(12345L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("仅申请人本人可撤回");
  }

  @Test
  @DisplayName("查询最新待审批单、待审批列表与历史流转记录")
  void testQueryApprovals() {
    ApprovalRecordDTO dto =
        new ApprovalRecordDTO(
            5001L,
            ApprovalTypeEnum.SPACE_LINK,
            1001L,
            1L,
            2L,
            999L,
            "张三",
            "申请跨部门文档联合检索",
            ApprovalStatusEnum.PENDING,
            null,
            null,
            null,
            null,
            "{\"documentIds\":[10,20]}",
            LocalDateTime.now());

    when(approvalRecordRepository.findFirstByApprovalTypeAndBizIdAndStatusOrderByCreateTimeDesc(
            ApprovalTypeEnum.SPACE_LINK, 1001L, ApprovalStatusEnum.PENDING))
        .thenReturn(Optional.of(spaceLinkRecord));
    when(approvalMapper.toDTO(spaceLinkRecord)).thenReturn(dto);

    Optional<ApprovalRecordDTO> opt =
        approvalService.getLatestPendingApproval(ApprovalTypeEnum.SPACE_LINK, 1001L);
    assertThat(opt).isPresent();
    assertThat(opt.get().id()).isEqualTo(5001L);

    when(approvalRecordRepository.findByTargetWorkspaceIdAndStatusOrderByCreateTimeDesc(
            2L, ApprovalStatusEnum.PENDING))
        .thenReturn(List.of(spaceLinkRecord));
    when(approvalMapper.toDTOList(List.of(spaceLinkRecord))).thenReturn(List.of(dto));

    List<ApprovalRecordDTO> pendingList = approvalService.listPendingApprovals(2L);
    assertThat(pendingList).hasSize(1);

    when(approvalRecordRepository.findAllByApprovalTypeAndBizIdOrderByCreateTimeDesc(
            ApprovalTypeEnum.SPACE_LINK, 1001L))
        .thenReturn(List.of(spaceLinkRecord));
    List<ApprovalRecordDTO> history =
        approvalService.listApprovalHistory(ApprovalTypeEnum.SPACE_LINK, 1001L);
    assertThat(history).hasSize(1);
  }
}
