package io.lexiloom.application.user.service.impl;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.enums.WorkSpaceRoleEnum;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.model.entity.UserEntity;
import io.lexiloom.application.user.model.entity.WorkSpaceEntity;
import io.lexiloom.application.user.model.entity.WorkSpaceMemberEntity;
import io.lexiloom.application.user.model.request.WorkSpaceMemberAddRequest;
import io.lexiloom.application.user.repository.UserRepository;
import io.lexiloom.application.user.repository.WorkSpaceMemberRepository;
import io.lexiloom.application.user.repository.WorkSpaceRepository;
import io.lexiloom.application.user.service.WorkSpaceMemberCommandService;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 团队成员写操作服务实现 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkSpaceMemberCommandServiceImpl implements WorkSpaceMemberCommandService {

  private final WorkSpaceRepository workSpaceRepository;
  private final WorkSpaceMemberRepository workSpaceMemberRepository;
  private final UserRepository userRepository;

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public Long addMember(Long workSpaceId, WorkSpaceMemberAddRequest request) {
    Long currentUserId = SecurityHolder.getUserId();
    WorkSpaceEntity workSpace = findWorkSpaceOrThrow(workSpaceId);

    // 1. 团队状态与管理权限校验
    workSpace.assertActive();
    assertCanManage(workSpaceId, currentUserId);

    // 2. 不能直接以 OWNER 角色添加成员
    if (request.role() == WorkSpaceRoleEnum.OWNER) {
      throw BusinessException.badRequest("不可直接指定所有者角色，请使用所有权转让功能");
    }

    // 3. 校验目标邮箱对应用户是否存在
    String email = StrUtil.trim(request.email());
    UserEntity targetUser =
        userRepository
            .findByEmail(email)
            .orElseThrow(() -> BusinessException.badRequest("该邮箱未注册用户: " + email));

    // 4. 如果是当前登录用户自身，则忽略本次添加操作
    Long targetUserId = targetUser.getId();
    if (Objects.equals(targetUserId, currentUserId)) {
      log.info(
          "【WorkSpaceMember】添加团队成员忽略操作人自身: workSpaceId={}, email={}, userId={}",
          workSpaceId,
          email,
          currentUserId);
      return null;
    }

    // 5. 校验团队人数上限
    long currentCount = workSpaceMemberRepository.countByWorkSpaceId(workSpaceId);
    workSpace.validateCanAddMember(currentCount);

    // 6. 幂等/防重复校验
    if (workSpaceMemberRepository.existsByWorkSpaceIdAndUserId(workSpaceId, targetUserId)) {
      throw BusinessException.badRequest("该用户已是团队成员: " + email);
    }

    // 7. 充血模型创建成员
    WorkSpaceMemberEntity member =
        WorkSpaceMemberEntity.create(workSpaceId, targetUserId, request.role());
    return workSpaceMemberRepository.save(member).getId();
  }

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public void updateMemberRole(Long workSpaceId, Long targetUserId, WorkSpaceRoleEnum newRole) {
    Long currentUserId = SecurityHolder.getUserId();
    WorkSpaceEntity workSpace = findWorkSpaceOrThrow(workSpaceId);
    workSpace.assertActive();

    // 只有 OWNER 或 ADMIN 可以管理成员角色
    WorkSpaceMemberEntity operator = findMemberOrThrow(workSpaceId, currentUserId, "您不是该团队成员");
    if (!operator.hasAtLeastRole(WorkSpaceRoleEnum.ADMIN)) {
      throw BusinessException.forbidden("仅团队所有者或管理员可修改成员角色");
    }

    // 不能通过此方法将成员改为 OWNER
    if (newRole == WorkSpaceRoleEnum.OWNER) {
      throw BusinessException.badRequest("不可直接设置为所有者，请使用所有权转让功能");
    }

    WorkSpaceMemberEntity targetMember = findMemberOrThrow(workSpaceId, targetUserId, "目标成员不存在");

    // ADMIN 不能修改其他 ADMIN 或 OWNER 的角色
    if (!operator.isOwner() && targetMember.hasAtLeastRole(WorkSpaceRoleEnum.ADMIN)) {
      throw BusinessException.forbidden("管理员无权修改同级管理员或所有者的角色");
    }

    targetMember.changeRole(newRole);
    workSpaceMemberRepository.save(targetMember);
  }

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public void removeMember(Long workSpaceId, Long targetUserId) {
    Long currentUserId = SecurityHolder.getUserId();
    WorkSpaceEntity workSpace = findWorkSpaceOrThrow(workSpaceId);
    workSpace.assertActive();

    WorkSpaceMemberEntity operator = findMemberOrThrow(workSpaceId, currentUserId, "您不是该团队成员");
    if (!operator.hasAtLeastRole(WorkSpaceRoleEnum.ADMIN)) {
      throw BusinessException.forbidden("仅团队所有者或管理员可移除成员");
    }

    WorkSpaceMemberEntity targetMember = findMemberOrThrow(workSpaceId, targetUserId, "目标成员不存在");

    if (targetMember.isOwner()) {
      throw BusinessException.badRequest("不可移除团队所有者");
    }

    if (!operator.isOwner() && targetMember.hasAtLeastRole(WorkSpaceRoleEnum.ADMIN)) {
      throw BusinessException.forbidden("管理员无权移除同级管理员");
    }

    workSpaceMemberRepository.deleteByWorkSpaceIdAndUserId(workSpaceId, targetUserId);
  }

  @Override
  @Transactional(rollbackFor = Throwable.class)
  public void leaveWorkSpace(Long workSpaceId) {
    Long currentUserId = SecurityHolder.getUserId();
    WorkSpaceEntity workSpace = findWorkSpaceOrThrow(workSpaceId);

    WorkSpaceMemberEntity member = findMemberOrThrow(workSpaceId, currentUserId, "您不是该团队成员");

    if (member.isOwner()) {
      throw BusinessException.badRequest("团队所有者不能直接退出团队，请先转让所有权或解散团队");
    }

    workSpaceMemberRepository.deleteByWorkSpaceIdAndUserId(workSpaceId, currentUserId);
  }

  private WorkSpaceEntity findWorkSpaceOrThrow(Long workSpaceId) {
    return workSpaceRepository
        .findById(workSpaceId)
        .orElseThrow(() -> BusinessException.badRequest("团队不存在: " + workSpaceId));
  }

  private WorkSpaceMemberEntity findMemberOrThrow(
      Long workSpaceId, Long userId, String notFoundMessage) {
    return workSpaceMemberRepository
        .findByWorkSpaceIdAndUserId(workSpaceId, userId)
        .orElseThrow(() -> BusinessException.badRequest(notFoundMessage));
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
