package io.lexiloom.application.ai.service;

import io.lexiloom.application.common.LoginUserAuthentication;
import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.document.api.dto.DocumentDTO;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
import io.lexiloom.application.enums.WorkSpaceRoleEnum;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.api.WorkSpaceMemberQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * AI 模块工作空间与文档访问权限统一校验器
 *
 * <p>统一收敛跨端点的工作空间成员身份校验、文档公开级别 ACL 判定及管理员特权检查。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiAccessSecurityValidator {

  private final WorkSpaceMemberQueryService workSpaceMemberQueryService;

  /** 判断当前凭证是否具备系统超级管理员或管理员角色 */
  public boolean isSystemAdmin(@Nullable LoginUserAuthentication user) {
    if (user == null || user.roles() == null) {
      return false;
    }
    return user.roles().contains("ADMIN") || user.roles().contains("SUPER_ADMIN");
  }

  /** 判断当前上下文中的用户是否具备系统超级管理员或管理员角色 */
  public boolean isCurrentSystemAdmin() {
    return isSystemAdmin(SecurityHolder.getLoginUser().orElse(null));
  }

  /** 断言是否有权访问指定工作空间 (系统管理员免检，普通用户必须为该空间成员) */
  public void assertCanAccessWorkspace(
      Long workspaceId, @Nullable Long userId, boolean isSystemAdmin) {
    if (workspaceId == null || isSystemAdmin) {
      return;
    }
    if (userId != null && !workSpaceMemberQueryService.isMember(workspaceId, userId)) {
      throw BusinessException.forbidden("您不是该工作空间成员，无权访问");
    }
  }

  /** 断言当前登录用户是否有权访问指定工作空间 (自动从当前安全上下文提取用户和权限) */
  public void assertCanAccessWorkspace(Long workspaceId) {
    if (workspaceId == null) {
      return;
    }
    LoginUserAuthentication user = SecurityHolder.getLoginUser().orElse(null);
    assertCanAccessWorkspace(workspaceId, user != null ? user.userId() : null, isSystemAdmin(user));
  }

  /** 断言是否有权读取指定文档 (根据公开级别 PUBLIC / INTERNAL / PRIVATE 进行细粒度判定) */
  public void assertCanReadDocument(DocumentDTO doc, @Nullable Long userId, boolean isSystemAdmin) {
    if (doc == null || isSystemAdmin) {
      return;
    }

    // 空间管理员或所有者拥有查看该空间下全部文档权限
    if (userId != null
        && workSpaceMemberQueryService.hasRole(
            doc.workSpaceId(), userId, WorkSpaceRoleEnum.ADMIN)) {
      return;
    }

    WorkSpaceAclEnum visibility =
        doc.visibility() != null ? doc.visibility() : WorkSpaceAclEnum.INTERNAL;

    switch (visibility) {
      case PUBLIC, INTERNAL -> {
        // PUBLIC 开放可见；INTERNAL 空间内可见（前置 assertCanAccessWorkspace 已保证空间访问权限）
      }
      case PRIVATE -> {
        if (userId != null && !userId.equals(doc.createBy())) {
          throw BusinessException.forbidden("您无权访问该私密文档");
        }
      }
    }
  }

  /** 断言当前登录用户是否有权读取指定文档 (自动从当前安全上下文提取用户和权限) */
  public void assertCanReadDocument(DocumentDTO doc) {
    if (doc == null) {
      return;
    }
    LoginUserAuthentication user = SecurityHolder.getLoginUser().orElse(null);
    assertCanReadDocument(doc, user != null ? user.userId() : null, isSystemAdmin(user));
  }
}
