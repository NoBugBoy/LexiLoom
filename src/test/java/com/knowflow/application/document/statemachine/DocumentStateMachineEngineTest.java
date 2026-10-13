package com.knowflow.application.document.statemachine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.knowflow.application.document.model.entity.DocumentEntity;
import com.knowflow.application.document.version.DocVersionService;
import com.knowflow.application.enums.WorkSpaceAclEnum;
import com.knowflow.application.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DocumentStateMachineEngineTest {

  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private DocVersionService docVersionService;

  private DocumentStateMachineEngine engine;

  @BeforeEach
  void setUp() {
    engine = new DocumentStateMachineEngine(eventPublisher, docVersionService);
    engine.init();
  }

  @Test
  @DisplayName("正常审批流转：待审核 -> 审批通过，并触发 Action 业务副作用（快照生成）")
  void testNormalReviewPipeline() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 0L, "设计规范", "摘要", "正文内容", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 100L);

    DocumentStateContext ctx = DocumentStateContext.of(100L, doc, 10L, "ADMIN", "申请发布");

    // 1. PENDING_APPROVAL -> APPROVED (审批通过)
    DocumentStateEnum state =
        engine.fire(DocumentStateEnum.PENDING_APPROVAL, DocumentEventEnum.APPROVE, ctx);
    assertThat(state).isEqualTo(DocumentStateEnum.APPROVED);
    verify(eventPublisher).publishEvent(any(DocumentStateTransitionEvent.class));

    // 验证 Action 副作用：自动创建版本快照
    verify(docVersionService).createSnapshot(eq(100L), anyString(), eq("申请发布"), eq(10L));
  }

  @Test
  @DisplayName("驳回与重新提交：待审核 -> 审批驳回 -> 待审核")
  void testRejectAndResubmitPipeline() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 0L, "设计规范", "摘要", "正文内容", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 100L);

    DocumentStateContext ctx = DocumentStateContext.of(100L, doc, 10L, "ADMIN", "内容需补全");

    // 1. PENDING_APPROVAL -> REJECTED (审批驳回，填了原因，Condition 通过)
    DocumentStateEnum state1 =
        engine.fire(DocumentStateEnum.PENDING_APPROVAL, DocumentEventEnum.REJECT, ctx);
    assertThat(state1).isEqualTo(DocumentStateEnum.REJECTED);

    // 2. REJECTED -> PENDING_APPROVAL (修改后重新提交)
    DocumentStateEnum state2 =
        engine.fire(DocumentStateEnum.REJECTED, DocumentEventEnum.SUBMIT, ctx);
    assertThat(state2).isEqualTo(DocumentStateEnum.PENDING_APPROVAL);
  }

  @Test
  @DisplayName("审批通过文档流转：审批通过 -> 重新提交审核")
  void testApprovedToResubmit() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 0L, "已发布文档", "摘要", "正文内容", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 101L);

    DocumentStateContext resubmitCtx = DocumentStateContext.of(101L, doc, 10L, "MEMBER", "修改后重提");
    DocumentStateEnum pendingState =
        engine.fire(DocumentStateEnum.APPROVED, DocumentEventEnum.SUBMIT, resubmitCtx);
    assertThat(pendingState).isEqualTo(DocumentStateEnum.PENDING_APPROVAL);
  }

  @Test
  @DisplayName("Condition 拦截校验：驳回时未填写原因将被 Condition 拦截")
  void testRejectWithoutReasonBlockedByCondition() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 0L, "设计规范", "摘要", "正文内容", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 100L);

    DocumentStateContext blankReasonCtx = DocumentStateContext.of(100L, doc, 10L, "ADMIN", "   ");

    assertThatThrownBy(
            () ->
                engine.fire(
                    DocumentStateEnum.PENDING_APPROVAL, DocumentEventEnum.REJECT, blankReasonCtx))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("无法执行 [审批驳回] 操作");
  }

  @Test
  @DisplayName("非法逆向流转拦截：已驳回状态无法直接审批通过（必须先提交审批）")
  void testIllegalTransition_RejectedCannotApproveDirectly() {
    DocumentStateContext ctx = DocumentStateContext.of(1L, 100L, "非法直批");

    assertThatThrownBy(
            () -> engine.fire(DocumentStateEnum.REJECTED, DocumentEventEnum.APPROVE, ctx))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("无法执行 [审批通过] 操作");
  }

  @Test
  @DisplayName("非法流转拦截：审批通过状态不可重复审批通过")
  void testIllegalTransition_ApprovedCannotApprove() {
    DocumentStateContext ctx = DocumentStateContext.of(1L, 100L, "重复通过");

    assertThatThrownBy(
            () -> engine.fire(DocumentStateEnum.APPROVED, DocumentEventEnum.APPROVE, ctx))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("无法执行 [审批通过] 操作");
  }

  @Test
  @DisplayName("权限拦截：普通成员（MEMBER）不能审批通过待审核文档")
  void testMemberCannotApprove() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 0L, "设计规范", "摘要", "正文内容", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 100L);
    DocumentStateContext ctx = DocumentStateContext.of(100L, doc, 10L, "MEMBER", "自己批自己");

    assertThatThrownBy(
            () -> engine.fire(DocumentStateEnum.PENDING_APPROVAL, DocumentEventEnum.APPROVE, ctx))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("无法执行 [审批通过] 操作");
  }

  @Test
  @DisplayName("管理员权限：管理员可以审批文档（支持自审自批发布）")
  void testAdminCanApprove() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 0L, "设计规范", "摘要", "正文内容", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 100L);
    ReflectionTestUtils.setField(doc, "createBy", 10L);
    DocumentStateContext ctx = DocumentStateContext.of(100L, doc, 10L, "ADMIN", "自审自批");

    DocumentStateEnum state =
        engine.fire(DocumentStateEnum.PENDING_APPROVAL, DocumentEventEnum.APPROVE, ctx);
    assertThat(state).isEqualTo(DocumentStateEnum.APPROVED);
  }
}
