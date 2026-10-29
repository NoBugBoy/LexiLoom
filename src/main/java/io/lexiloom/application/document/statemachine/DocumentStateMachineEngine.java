package io.lexiloom.application.document.statemachine;

import io.lexiloom.application.document.api.dto.DocumentEventEnum;
import io.lexiloom.application.document.api.dto.DocumentStateEnum;
import io.lexiloom.application.document.model.event.DocumentStateTransitionEvent;

import cn.hutool.core.util.StrUtil;
import com.alibaba.cola.statemachine.Action;
import com.alibaba.cola.statemachine.Condition;
import com.alibaba.cola.statemachine.StateMachine;
import com.alibaba.cola.statemachine.builder.StateMachineBuilder;
import com.alibaba.cola.statemachine.builder.StateMachineBuilderFactory;
import io.lexiloom.application.exception.ErrorCode;
import io.lexiloom.application.document.model.entity.DocumentEntity;
import io.lexiloom.application.document.version.DocVersionService;
import io.lexiloom.application.enums.WorkSpaceRoleEnum;
import io.lexiloom.application.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 基于 Alibaba COLA StateMachine 封装的文档生命周期状态机执行引擎 遵循 DDD 状态机驱动业务模式： 1. Condition
 * 真正承担领域规则前置拦截（如驳回必填意见、标题内容完整性约束）； 2. Action 真正承载状态流转时的领域业务副作用（如版本快照触发、Redis 草稿清理、事件发布与审计）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentStateMachineEngine {

  public static final String MACHINE_ID = "DOCUMENT_STATE_MACHINE";

  private final ApplicationEventPublisher eventPublisher;
  private final DocVersionService docVersionService;

  private StateMachine<DocumentStateEnum, DocumentEventEnum, DocumentStateContext> stateMachine;

  @PostConstruct
  public void init() {
    StateMachineBuilder<DocumentStateEnum, DocumentEventEnum, DocumentStateContext> builder =
        StateMachineBuilderFactory.create();

    // 1. PENDING_APPROVAL -> APPROVED (审批通过)
    builder
        .externalTransition()
        .from(DocumentStateEnum.PENDING_APPROVAL)
        .to(DocumentStateEnum.APPROVED)
        .on(DocumentEventEnum.APPROVE)
        .when(checkApproveCondition())
        .perform(doPublishAction());

    // 2. PENDING_APPROVAL -> REJECTED (审批驳回)
    builder
        .externalTransition()
        .from(DocumentStateEnum.PENDING_APPROVAL)
        .to(DocumentStateEnum.REJECTED)
        .on(DocumentEventEnum.REJECT)
        .when(checkRejectCondition())
        .perform(doRejectAction());

    // 3. REJECTED -> PENDING_APPROVAL (驳回修改后重新发起审批)
    builder
        .externalTransition()
        .from(DocumentStateEnum.REJECTED)
        .to(DocumentStateEnum.PENDING_APPROVAL)
        .on(DocumentEventEnum.SUBMIT)
        .when(checkSubmitCondition())
        .perform(doSubmitAction());

    // 4. APPROVED -> PENDING_APPROVAL (审批通过后重新提交审核)
    builder
        .externalTransition()
        .from(DocumentStateEnum.APPROVED)
        .to(DocumentStateEnum.PENDING_APPROVAL)
        .on(DocumentEventEnum.SUBMIT)
        .when(checkSubmitCondition())
        .perform(doSubmitAction());

    String instanceId = MACHINE_ID + "_" + System.identityHashCode(this);
    this.stateMachine = builder.build(instanceId);
    log.info("COLA StateMachine [{}] 初始化完成", instanceId);
  }

  /** 触发状态机事件流转 */
  public DocumentStateEnum fire(
      DocumentStateEnum currentState, DocumentEventEnum event, DocumentStateContext context) {
    if (currentState == null || event == null) {
      throw new BusinessException(ErrorCode.Document.STATE_MACHINE_ERROR, "状态机输入的状态或事件不能为空");
    }

    DocumentStateEnum targetState = stateMachine.fireEvent(currentState, event, context);

    if (targetState == null || targetState == currentState) {
      log.warn(
          "文档状态非法流转或Condition校验未通过: docId={}, currentState={}, event={}",
          context != null ? context.documentId() : null,
          currentState,
          event);
      throw new BusinessException(
          ErrorCode.Document.STATE_MACHINE_ERROR,
          String.format(
              "文档当前处于 [%s] 状态，无法执行 [%s] 操作（前置条件校验未通过或非法状态流转）",
              currentState.getDescription(), event.getDescription()));
    }

    log.info(
        "文档状态流转成功: docId={}, {} -> {} (事件: {})",
        context != null ? context.documentId() : null,
        currentState,
        targetState,
        event);

    // 状态流转成功后统一发布领域事件
    publishTransitionEvent(currentState, targetState, event, context);

    return targetState;
  }

  // ===================== Condition 业务规则校验 =====================

  /** 提交审阅条件：操作人具备成员及以上角色，且文档标题非空 */
  private Condition<DocumentStateContext> checkSubmitCondition() {
    return ctx -> hasRoleAtLeast(ctx, WorkSpaceRoleEnum.MEMBER) && hasNonBlankTitle(ctx);
  }

  /** 驳回前置条件：具备审批权限（管理员及以上角色），且必须填写驳回原因/修改意见 */
  private Condition<DocumentStateContext> checkRejectCondition() {
    return ctx -> checkApproverCondition(ctx) && StrUtil.isNotBlank(ctx.comment());
  }

  /** 审批通过条件：具备审批权限（管理员及以上角色），且文档不能处于排队或异步解析未完成状态 */
  private Condition<DocumentStateContext> checkApproveCondition() {
    return ctx -> checkApproverCondition(ctx) && isParseReady(ctx);
  }

  private boolean isParseReady(DocumentStateContext ctx) {
    if (ctx == null || ctx.document() == null) {
      return false;
    }
    io.lexiloom.application.document.parser.DocParseStatusEnum status =
        ctx.document().getParseStatus();
    return status != io.lexiloom.application.document.parser.DocParseStatusEnum.QUEUED
        && status != io.lexiloom.application.document.parser.DocParseStatusEnum.ASYNC_PROCESSING;
  }

  // ===================== Condition 复用断言（角色与归属校验） =====================

  /** 审批权限断言：管理员及以上角色（ADMIN / OWNER 具备审批与自审发布权限） */
  private boolean checkApproverCondition(DocumentStateContext ctx) {
    return hasRoleAtLeast(ctx, WorkSpaceRoleEnum.ADMIN);
  }

  /** 角色等级断言：解析上下文中的操作人角色并做等级比较，解析失败按无权限处理 */
  private static boolean hasRoleAtLeast(DocumentStateContext ctx, WorkSpaceRoleEnum requiredRole) {
    if (ctx == null || ctx.operatorId() == null || StrUtil.isBlank(ctx.operatorRole())) {
      return false;
    }
    try {
      WorkSpaceRoleEnum actualRole =
          WorkSpaceRoleEnum.valueOf(ctx.operatorRole().trim().toUpperCase(Locale.ROOT));
      return actualRole.getLevel() >= requiredRole.getLevel();
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  /** 标题非空断言（上下文未携带文档主体时跳过，交由上层服务校验） */
  private static boolean hasNonBlankTitle(DocumentStateContext ctx) {
    return ctx.document() == null || StrUtil.isNotBlank(ctx.document().getTitle());
  }

  // ===================== Action 业务副作用执行 =====================

  /** 提交审批动作 */
  private Action<DocumentStateEnum, DocumentEventEnum, DocumentStateContext> doSubmitAction() {
    return (from, to, event, ctx) -> {
      log.info(
          "【Action:Submit】文档提交审批中: docId={}, operatorId={}", ctx.documentId(), ctx.operatorId());
    };
  }

  /** 发布上线动作：固化审批通过版本、自动生成/同步版本快照 */
  private Action<DocumentStateEnum, DocumentEventEnum, DocumentStateContext> doPublishAction() {
    return (from, to, event, ctx) -> {
      log.info(
          "【Action:Publish】文档正式发布执行: docId={}, operatorId={}", ctx.documentId(), ctx.operatorId());

      DocumentEntity doc = ctx.document();
      if (doc != null) {
        // 1. 聚合根状态变更与已审批版本号固化
        doc.publish();

        // 2. 自动生成不可变版本快照
        if (docVersionService != null) {
          docVersionService.createSnapshot(
              doc.getId(),
              doc.getCurrentVersionTag(),
              StrUtil.blankToDefault(ctx.comment(), "发布正式版本自动快照"),
              ctx.operatorId());
        }
      }
    };
  }

  /** 审批驳回动作 */
  private Action<DocumentStateEnum, DocumentEventEnum, DocumentStateContext> doRejectAction() {
    return (from, to, event, ctx) -> {
      log.info("【Action:Reject】文档审批已驳回: docId={}, reason={}", ctx.documentId(), ctx.comment());
    };
  }

  private void publishTransitionEvent(
      DocumentStateEnum from,
      DocumentStateEnum to,
      DocumentEventEnum event,
      DocumentStateContext ctx) {
    if (eventPublisher != null && ctx != null) {
      eventPublisher.publishEvent(
          DocumentStateTransitionEvent.of(ctx.documentId(), from, to, event, ctx));
    }
  }
}
