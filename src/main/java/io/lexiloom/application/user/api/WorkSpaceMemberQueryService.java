package io.lexiloom.application.user.api;

import io.lexiloom.application.enums.WorkSpaceRoleEnum;
import io.lexiloom.application.user.api.dto.WorkSpaceMemberDTO;
import io.lexiloom.application.user.model.request.WorkSpaceMemberPageRequest;
import io.lexiloom.application.user.model.response.WorkSpaceMemberResponse;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;

/** 团队成员查询服务（跨模块公开） */
public interface WorkSpaceMemberQueryService {

  /** 查询指定团队中的成员信息 */
  Optional<WorkSpaceMemberDTO> getMember(Long workSpaceId, Long userId);

  /** 分页查询团队成员 */
  Page<WorkSpaceMemberResponse> pageMembers(Long workSpaceId, WorkSpaceMemberPageRequest request);

  /** 查询团队所有成员 */
  List<WorkSpaceMemberResponse> listMembers(Long workSpaceId);

  /** 校验用户是否为团队成员 */
  boolean isMember(Long workSpaceId, Long userId);

  /** 校验用户在团队中是否拥有不低于 requiredRole 的权限 */
  boolean hasRole(Long workSpaceId, Long userId, WorkSpaceRoleEnum requiredRole);

  /** 统计团队当前有效成员总数 */
  long countMembers(Long workSpaceId);

  /** 统计团队指定角色的成员数量 */
  long countMembersByRole(Long workSpaceId, WorkSpaceRoleEnum role);
}
