package com.knowflow.application.user.api;

import com.knowflow.application.enums.WorkSpaceRoleEnum;
import com.knowflow.application.user.api.dto.WorkSpaceMemberDTO;
import com.knowflow.application.user.model.request.WorkSpaceMemberPageRequest;
import com.knowflow.application.user.model.response.WorkSpaceMemberResponse;
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
}
