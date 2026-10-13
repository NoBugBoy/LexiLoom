package com.knowflow.application.user.service.impl;

import com.knowflow.application.common.SecurityHolder;
import com.knowflow.application.enums.WorkSpaceRoleEnum;
import com.knowflow.application.exception.BusinessException;
import com.knowflow.application.user.model.entity.WorkSpaceEntity;
import com.knowflow.application.user.model.entity.WorkSpaceMemberEntity;
import com.knowflow.application.user.model.request.WorkSpaceCreateRequest;
import com.knowflow.application.user.model.request.WorkSpaceUpdateRequest;
import com.knowflow.application.user.repository.WorkSpaceMemberRepository;
import com.knowflow.application.user.repository.WorkSpaceRepository;
import com.knowflow.application.user.service.WorkSpaceCommandService;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 团队写操作服务实现 */
@Service
@RequiredArgsConstructor
public class WorkSpaceCommandServiceImpl implements WorkSpaceCommandService {

  private final WorkSpaceRepository workSpaceRepository;
  private final WorkSpaceMemberRepository workSpaceMemberRepository;

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public Long create(WorkSpaceCreateRequest request) {
    Long currentUserId = SecurityHolder.getUserId();

    // 1. 唯一性校验
    if (workSpaceRepository.existsByCode(request.code().trim().toLowerCase())) {
      throw BusinessException.badRequest("团队唯一标识(code)已存在: " + request.code());
    }

    // 2. 充血实体构建并校验前置条件
    WorkSpaceEntity workSpace =
        WorkSpaceEntity.create(
            request.name(),
            request.code(),
            request.description(),
            request.avatarUrl(),
            currentUserId,
            request.maxMembers(),
            request.visibility());

    WorkSpaceEntity saved = workSpaceRepository.save(workSpace);

    // 3. 将创建人自动绑定为 OWNER 成员
    WorkSpaceMemberEntity ownerMember =
        WorkSpaceMemberEntity.create(saved.getId(), currentUserId, WorkSpaceRoleEnum.OWNER);
    workSpaceMemberRepository.save(ownerMember);

    return saved.getId();
  }

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public void update(Long workSpaceId, WorkSpaceUpdateRequest request) {
    Long currentUserId = SecurityHolder.getUserId();
    WorkSpaceEntity workSpace = findWorkSpaceOrThrow(workSpaceId);

    // 权限校验：仅 OWNER 或 ADMIN 可修改团队基本信息
    assertCanManage(workSpaceId, currentUserId);

    workSpace.updateInfo(
        request.name(), request.description(), request.avatarUrl(), request.visibility());
    workSpaceRepository.save(workSpace);
  }

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public void updateMaxMembers(Long workSpaceId, Integer maxMembers) {
    Long currentUserId = SecurityHolder.getUserId();
    WorkSpaceEntity workSpace = findWorkSpaceOrThrow(workSpaceId);

    // 仅所有者可调整人数配额
    assertIsOwner(workSpace, currentUserId);

    long currentMemberCount = workSpaceMemberRepository.countByWorkSpaceId(workSpaceId);
    workSpace.updateMaxMembers(maxMembers, currentMemberCount);
    workSpaceRepository.save(workSpace);
  }

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public void freeze(Long workSpaceId) {
    Long currentUserId = SecurityHolder.getUserId();
    WorkSpaceEntity workSpace = findWorkSpaceOrThrow(workSpaceId);

    assertIsOwner(workSpace, currentUserId);

    workSpace.freeze();
    workSpaceRepository.save(workSpace);
  }

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public void activate(Long workSpaceId) {
    Long currentUserId = SecurityHolder.getUserId();
    WorkSpaceEntity workSpace = findWorkSpaceOrThrow(workSpaceId);

    assertIsOwner(workSpace, currentUserId);

    workSpace.activate();
    workSpaceRepository.save(workSpace);
  }

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public void disband(Long workSpaceId) {
    Long currentUserId = SecurityHolder.getUserId();
    WorkSpaceEntity workSpace = findWorkSpaceOrThrow(workSpaceId);

    assertIsOwner(workSpace, currentUserId);

    workSpace.disband();
    workSpaceRepository.save(workSpace);
  }

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public void delete(Long workSpaceId) {
    Long currentUserId = SecurityHolder.getUserId();
    WorkSpaceEntity workSpace = findWorkSpaceOrThrow(workSpaceId);

    assertIsOwner(workSpace, currentUserId);

    workSpaceRepository.deleteById(workSpaceId);
    workSpaceMemberRepository.deleteByWorkSpaceId(workSpaceId);
  }

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public void transferOwnership(Long workSpaceId, Long newOwnerId) {
    Objects.requireNonNull(newOwnerId, "新所有者ID不能为空");
    Long currentUserId = SecurityHolder.getUserId();
    WorkSpaceEntity workSpace = findWorkSpaceOrThrow(workSpaceId);

    assertIsOwner(workSpace, currentUserId);

    // 校验新所有者必须是团队已有成员
    WorkSpaceMemberEntity newOwnerMember =
        workSpaceMemberRepository
            .findByWorkSpaceIdAndUserId(workSpaceId, newOwnerId)
            .orElseThrow(() -> BusinessException.badRequest("目标用户不是团队成员，无法转让所有权"));

    // 获取原所有者的成员记录
    WorkSpaceMemberEntity currentOwnerMember =
        workSpaceMemberRepository
            .findByWorkSpaceIdAndUserId(workSpaceId, currentUserId)
            .orElseThrow(() -> BusinessException.badRequest("当前所有者成员记录不存在"));

    // 聚合根内部状态流转
    workSpace.transferOwnership(newOwnerId);
    workSpaceRepository.save(workSpace);

    // 调整成员角色
    newOwnerMember.changeRole(WorkSpaceRoleEnum.OWNER);
    currentOwnerMember.demoteFromOwner(WorkSpaceRoleEnum.ADMIN);
    workSpaceMemberRepository.save(newOwnerMember);
    workSpaceMemberRepository.save(currentOwnerMember);
  }

  private WorkSpaceEntity findWorkSpaceOrThrow(Long workSpaceId) {
    return workSpaceRepository
        .findById(workSpaceId)
        .orElseThrow(() -> BusinessException.badRequest("团队不存在: " + workSpaceId));
  }

  private void assertIsOwner(WorkSpaceEntity workSpace, Long currentUserId) {
    if (!workSpace.isOwner(currentUserId)) {
      throw BusinessException.forbidden("仅团队所有者有权执行此操作");
    }
  }

  private void assertCanManage(Long workSpaceId, Long currentUserId) {
    WorkSpaceMemberEntity member =
        workSpaceMemberRepository
            .findByWorkSpaceIdAndUserId(workSpaceId, currentUserId)
            .orElseThrow(() -> BusinessException.forbidden("您不是该团队成员，无权操作"));

    if (!member.hasAtLeastRole(WorkSpaceRoleEnum.ADMIN)) {
      throw BusinessException.forbidden("仅团队所有者或管理员有权执行此操作");
    }
  }
}
