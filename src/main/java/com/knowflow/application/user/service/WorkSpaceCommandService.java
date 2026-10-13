package com.knowflow.application.user.service;

import com.knowflow.application.user.model.request.WorkSpaceCreateRequest;
import com.knowflow.application.user.model.request.WorkSpaceUpdateRequest;

/** 团队写操作服务 */
public interface WorkSpaceCommandService {

  /**
   * 创建团队并初始化创建者为 OWNER 成员
   *
   * @param request 创建参数
   * @return 新建团队ID
   */
  Long create(WorkSpaceCreateRequest request);

  /** 修改团队基本信息 */
  void update(Long workSpaceId, WorkSpaceUpdateRequest request);

  /** 调整团队成员上限 */
  void updateMaxMembers(Long workSpaceId, Integer maxMembers);

  /** 冻结团队 */
  void freeze(Long workSpaceId);

  /** 恢复团队 */
  void activate(Long workSpaceId);

  /** 解散团队 */
  void disband(Long workSpaceId);

  /** 删除团队（软删除团队及所有成员） */
  void delete(Long workSpaceId);

  /** 转让团队所有权 */
  void transferOwnership(Long workSpaceId, Long newOwnerId);
}
