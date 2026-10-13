package com.knowflow.application.user.service;

import com.knowflow.application.enums.WorkSpaceRoleEnum;
import com.knowflow.application.user.model.request.WorkSpaceMemberAddRequest;

/** 团队成员写操作服务 */
public interface WorkSpaceMemberCommandService {

  /**
   * 添加团队成员
   *
   * @param workSpaceId 团队ID
   * @param request 添加成员请求
   * @return 成员记录ID
   */
  Long addMember(Long workSpaceId, WorkSpaceMemberAddRequest request);

  /** 更新成员角色 */
  void updateMemberRole(Long workSpaceId, Long targetUserId, WorkSpaceRoleEnum newRole);

  /** 移除团队成员 */
  void removeMember(Long workSpaceId, Long targetUserId);

  /** 当前用户主动退出团队 */
  void leaveWorkSpace(Long workSpaceId);
}
