package io.lexiloom.application.document.acl;

import io.lexiloom.application.exception.ErrorCode;
import io.lexiloom.application.document.model.entity.DocumentEntity;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
import io.lexiloom.application.enums.WorkSpaceRoleEnum;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.api.WorkSpaceMemberQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 文档细粒度 ACL 访问控制断言服务 根据文档公开级别 (PUBLIC / INTERNAL / PRIVATE) 及工作区成员角色实现严格权限拦截
 *
 * <p>依赖 {@link WorkSpaceMemberQueryService} 公开 API 接口访问团队成员信息， 遵循 Spring Modulith 模块封装规范，禁止跨模块直接访问
 * Repository。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocAccessControlService {

  private final WorkSpaceMemberQueryService workSpaceMemberQueryService;

  /** 断言是否有权读取该文档 */
  public void assertCanRead(DocumentEntity doc, Long userId) {
    if (!canRead(doc, userId)) {
      log.warn(
          "【DocACL】用户无权查看文档: docId={}, userId={}, visibility={}",
          doc.getId(),
          userId,
          doc.getVisibility());
      throw new BusinessException(ErrorCode.Document.DOC_ACCESS_DENIED, "无权查看该文档");
    }
  }

  /** 断言是否有权编辑修改该文档 */
  public void assertCanWrite(DocumentEntity doc, Long userId) {
    if (!canWrite(doc, userId)) {
      log.warn(
          "【DocACL】用户无权编辑文档: docId={}, userId={}, visibility={}",
          doc.getId(),
          userId,
          doc.getVisibility());
      throw new BusinessException(ErrorCode.Document.DOC_ACCESS_DENIED, "无权编辑该文档");
    }
  }

  /** 判断是否有读取权限 */
  public boolean canRead(DocumentEntity doc, Long userId) {
    if (doc == null || userId == null) {
      return false;
    }

    WorkSpaceAclEnum visibility = doc.getVisibility();
    if (visibility == null) {
      visibility = WorkSpaceAclEnum.INTERNAL;
    }

    // 1. PUBLIC: 登录用户均可查阅
    if (visibility == WorkSpaceAclEnum.PUBLIC) {
      return true;
    }

    boolean isMember = workSpaceMemberQueryService.isMember(doc.getWorkSpaceId(), userId);

    // 2. INTERNAL: 仅当前团队空间成员可查阅
    if (visibility == WorkSpaceAclEnum.INTERNAL) {
      return isMember;
    }

    // 3. PRIVATE: 仅文档创建者、或者团队 OWNER / ADMIN 可查阅
    if (visibility == WorkSpaceAclEnum.PRIVATE) {
      if (userId.equals(doc.getCreateBy())) {
        return true;
      }
      return workSpaceMemberQueryService.hasRole(
          doc.getWorkSpaceId(), userId, WorkSpaceRoleEnum.ADMIN);
    }

    return false;
  }

  /** 判断是否有编辑权限 */
  public boolean canWrite(DocumentEntity doc, Long userId) {
    if (doc == null || userId == null) {
      return false;
    }

    WorkSpaceAclEnum visibility = doc.getVisibility();
    if (visibility == null) {
      visibility = WorkSpaceAclEnum.INTERNAL;
    }

    // 如果用户非本工作空间成员，不可编辑
    if (!workSpaceMemberQueryService.isMember(doc.getWorkSpaceId(), userId)) {
      return false;
    }

    // PRIVATE: 仅文档创建者或团队 OWNER/ADMIN 可编辑
    if (visibility == WorkSpaceAclEnum.PRIVATE) {
      if (userId.equals(doc.getCreateBy())) {
        return true;
      }
      return workSpaceMemberQueryService.hasRole(
          doc.getWorkSpaceId(), userId, WorkSpaceRoleEnum.ADMIN);
    }

    // PUBLIC / INTERNAL: 需要是工作区普通成员或以上
    return workSpaceMemberQueryService.hasRole(
        doc.getWorkSpaceId(), userId, WorkSpaceRoleEnum.MEMBER);
  }

  /** 解析用户在工作空间内的角色名称 */
  public String resolveUserRole(Long workSpaceId, Long userId) {
    if (workSpaceId == null || userId == null) {
      return WorkSpaceRoleEnum.MEMBER.name();
    }
    return workSpaceMemberQueryService
        .getMember(workSpaceId, userId)
        .map(m -> m.role().name())
        .orElse(WorkSpaceRoleEnum.MEMBER.name());
  }

  /** 断言是否具有工作空间阅读权限 */
  public void assertCanReadWorkSpace(Long workSpaceId, Long userId) {
    if (workSpaceId == null || userId == null) {
      throw new BusinessException(ErrorCode.Document.DOC_ACCESS_DENIED, "无权访问此工作区文档");
    }
    if (!workSpaceMemberQueryService.isMember(workSpaceId, userId)) {
      throw new BusinessException(ErrorCode.Document.DOC_ACCESS_DENIED, "您不是该工作空间成员，无权查看文档列表");
    }
  }

  /** 断言是否具有工作空间写入权限（MEMBER 及以上角色，访客 GUEST 不可创建/导入文档） */
  public void assertCanWriteWorkSpace(Long workSpaceId, Long userId) {
    if (!isWorkspaceMemberOrAbove(workSpaceId, userId)) {
      log.warn("【DocACL】用户无工作空间写权限: workSpaceId={}, userId={}", workSpaceId, userId);
      throw new BusinessException(ErrorCode.Document.DOC_ACCESS_DENIED, "您不是该工作空间成员或权限不足，无权执行该操作");
    }
  }

  /** 判断用户是否为工作空间管理员及以上（OWNER / ADMIN） */
  public boolean isWorkspaceAdmin(Long workSpaceId, Long userId) {
    if (workSpaceId == null || userId == null) {
      return false;
    }
    return workSpaceMemberQueryService.hasRole(workSpaceId, userId, WorkSpaceRoleEnum.ADMIN);
  }

  private boolean isWorkspaceMemberOrAbove(Long workSpaceId, Long userId) {
    if (workSpaceId == null || userId == null) {
      return false;
    }
    return workSpaceMemberQueryService.hasRole(workSpaceId, userId, WorkSpaceRoleEnum.MEMBER);
  }
}
