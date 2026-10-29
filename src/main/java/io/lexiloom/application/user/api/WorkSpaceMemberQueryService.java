package io.lexiloom.application.user.api;

import io.lexiloom.application.enums.WorkSpaceRoleEnum;
import io.lexiloom.application.user.api.dto.WorkSpaceMemberDTO;
import java.util.Optional;

/** 团队成员查询服务（跨模块公开，仅暴露 DTO） */
public interface WorkSpaceMemberQueryService {

  /** 查询指定团队中的成员信息 */
  Optional<WorkSpaceMemberDTO> getMember(Long workSpaceId, Long userId);

  /** 校验用户是否为团队成员 */
  boolean isMember(Long workSpaceId, Long userId);

  /** 校验用户在团队中是否拥有不低于 requiredRole 的权限 */
  boolean hasRole(Long workSpaceId, Long userId, WorkSpaceRoleEnum requiredRole);

  /** 统计团队当前有效成员总数 */
  long countMembers(Long workSpaceId);

  /** 统计团队指定角色的成员数量 */
  long countMembersByRole(Long workSpaceId, WorkSpaceRoleEnum role);
}
