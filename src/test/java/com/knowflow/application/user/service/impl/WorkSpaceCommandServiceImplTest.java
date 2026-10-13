package com.knowflow.application.user.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.knowflow.application.common.LoginUserAuthentication;
import com.knowflow.application.enums.WorkSpaceAclEnum;
import com.knowflow.application.enums.WorkSpaceRoleEnum;
import com.knowflow.application.enums.WorkSpaceStatusEnum;
import com.knowflow.application.exception.BusinessException;
import com.knowflow.application.user.model.entity.WorkSpaceEntity;
import com.knowflow.application.user.model.entity.WorkSpaceMemberEntity;
import com.knowflow.application.user.model.request.WorkSpaceCreateRequest;
import com.knowflow.application.user.model.request.WorkSpaceUpdateRequest;
import com.knowflow.application.user.repository.WorkSpaceMemberRepository;
import com.knowflow.application.user.repository.WorkSpaceRepository;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class WorkSpaceCommandServiceImplTest {

  @Mock private WorkSpaceRepository workSpaceRepository;
  @Mock private WorkSpaceMemberRepository workSpaceMemberRepository;

  @InjectMocks private WorkSpaceCommandServiceImpl service;

  private final Long userId = 100L;

  @BeforeEach
  void setUp() {
    SecurityContextHolder.getContext()
        .setAuthentication(new LoginUserAuthentication(userId, Collections.emptyList()));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("创建团队成功 - 保存实体并自动绑定创建者为OWNER")
  void createSuccess() {
    WorkSpaceCreateRequest request =
        new WorkSpaceCreateRequest(
            "测试团队", "test-team", "简介", "http://avatar", 50, WorkSpaceAclEnum.PUBLIC);

    when(workSpaceRepository.existsByCode("test-team")).thenReturn(false);

    WorkSpaceEntity savedEntity =
        WorkSpaceEntity.create(
            request.name(),
            request.code(),
            request.description(),
            request.avatarUrl(),
            userId,
            request.maxMembers(),
            request.visibility());
    ReflectionTestUtils.setField(savedEntity, "id", 1L);

    when(workSpaceRepository.save(any(WorkSpaceEntity.class))).thenReturn(savedEntity);

    Long workSpaceId = service.create(request);

    assertThat(workSpaceId).isEqualTo(1L);
    verify(workSpaceRepository).save(any(WorkSpaceEntity.class));
    verify(workSpaceMemberRepository)
        .save(
            argThat(
                member ->
                    member.getWorkSpaceId().equals(1L)
                        && member.getUserId().equals(userId)
                        && member.getRole() == WorkSpaceRoleEnum.OWNER));
  }

  @Test
  @DisplayName("创建团队 - code已存在抛出异常")
  void createDuplicateCodeThrows() {
    WorkSpaceCreateRequest request =
        new WorkSpaceCreateRequest(
            "测试团队", "duplicate-code", "简介", null, 50, WorkSpaceAclEnum.PRIVATE);

    when(workSpaceRepository.existsByCode("duplicate-code")).thenReturn(true);

    assertThatThrownBy(() -> service.create(request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("团队唯一标识(code)已存在");

    verify(workSpaceRepository, never()).save(any());
  }

  @Test
  @DisplayName("更新团队信息成功")
  void updateSuccess() {
    WorkSpaceEntity entity =
        WorkSpaceEntity.create(
            "旧团队名", "old-team", "旧简介", null, userId, 50, WorkSpaceAclEnum.PRIVATE);
    ReflectionTestUtils.setField(entity, "id", 1L);

    WorkSpaceMemberEntity member =
        WorkSpaceMemberEntity.create(1L, userId, WorkSpaceRoleEnum.OWNER);

    when(workSpaceRepository.findById(1L)).thenReturn(Optional.of(entity));
    when(workSpaceMemberRepository.findByWorkSpaceIdAndUserId(1L, userId))
        .thenReturn(Optional.of(member));

    WorkSpaceUpdateRequest updateRequest =
        new WorkSpaceUpdateRequest("新团队名", "新简介", "http://new-avatar", WorkSpaceAclEnum.PUBLIC);
    service.update(1L, updateRequest);

    assertThat(entity.getName()).isEqualTo("新团队名");
    assertThat(entity.getDescription()).isEqualTo("新简介");
    assertThat(entity.getVisibility()).isEqualTo(WorkSpaceAclEnum.PUBLIC);
    verify(workSpaceRepository).save(entity);
  }

  @Test
  @DisplayName("非管理员/所有者无法更新团队")
  void updateForbiddenForNormalMember() {
    WorkSpaceEntity entity =
        WorkSpaceEntity.create("团队名", "team-code", "简介", null, 999L, 50, WorkSpaceAclEnum.PRIVATE);

    WorkSpaceMemberEntity member =
        WorkSpaceMemberEntity.create(1L, userId, WorkSpaceRoleEnum.MEMBER);

    when(workSpaceRepository.findById(1L)).thenReturn(Optional.of(entity));
    when(workSpaceMemberRepository.findByWorkSpaceIdAndUserId(1L, userId))
        .thenReturn(Optional.of(member));

    WorkSpaceUpdateRequest updateRequest = new WorkSpaceUpdateRequest("新团队名", null, null, null);

    assertThatThrownBy(() -> service.update(1L, updateRequest))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("仅团队所有者或管理员有权执行此操作");
  }

  @Test
  @DisplayName("调整人数配额成功")
  void updateMaxMembersSuccess() {
    WorkSpaceEntity entity =
        WorkSpaceEntity.create("团队名", "team-code", null, null, userId, 50, null);

    when(workSpaceRepository.findById(1L)).thenReturn(Optional.of(entity));
    when(workSpaceMemberRepository.countByWorkSpaceId(1L)).thenReturn(10L);

    service.updateMaxMembers(1L, 100);

    assertThat(entity.getMaxMembers()).isEqualTo(100);
    verify(workSpaceRepository).save(entity);
  }

  @Test
  @DisplayName("转让团队所有权成功")
  void transferOwnershipSuccess() {
    Long newOwnerId = 200L;
    WorkSpaceEntity entity =
        WorkSpaceEntity.create("团队名", "team-code", null, null, userId, 50, null);

    WorkSpaceMemberEntity currentOwnerMember =
        WorkSpaceMemberEntity.create(1L, userId, WorkSpaceRoleEnum.OWNER);
    WorkSpaceMemberEntity newOwnerMember =
        WorkSpaceMemberEntity.create(1L, newOwnerId, WorkSpaceRoleEnum.ADMIN);

    when(workSpaceRepository.findById(1L)).thenReturn(Optional.of(entity));
    when(workSpaceMemberRepository.findByWorkSpaceIdAndUserId(1L, newOwnerId))
        .thenReturn(Optional.of(newOwnerMember));
    when(workSpaceMemberRepository.findByWorkSpaceIdAndUserId(1L, userId))
        .thenReturn(Optional.of(currentOwnerMember));

    service.transferOwnership(1L, newOwnerId);

    assertThat(entity.getOwnerId()).isEqualTo(newOwnerId);
    assertThat(newOwnerMember.getRole()).isEqualTo(WorkSpaceRoleEnum.OWNER);
    assertThat(currentOwnerMember.getRole()).isEqualTo(WorkSpaceRoleEnum.ADMIN);
    verify(workSpaceRepository).save(entity);
    verify(workSpaceMemberRepository).save(newOwnerMember);
    verify(workSpaceMemberRepository).save(currentOwnerMember);
  }

  @Test
  @DisplayName("冻结团队成功")
  void freezeSuccess() {
    WorkSpaceEntity entity =
        WorkSpaceEntity.create("团队名", "team-code", null, null, userId, 50, null);

    when(workSpaceRepository.findById(1L)).thenReturn(Optional.of(entity));

    service.freeze(1L);

    assertThat(entity.getStatus()).isEqualTo(WorkSpaceStatusEnum.FROZEN);
    verify(workSpaceRepository).save(entity);
  }

  @Test
  @DisplayName("解散团队成功")
  void disbandSuccess() {
    WorkSpaceEntity entity =
        WorkSpaceEntity.create("团队名", "team-code", null, null, userId, 50, null);

    when(workSpaceRepository.findById(1L)).thenReturn(Optional.of(entity));

    service.disband(1L);

    assertThat(entity.getStatus()).isEqualTo(WorkSpaceStatusEnum.DISBANDED);
    verify(workSpaceRepository).save(entity);
  }

  @Test
  @DisplayName("删除团队成功")
  void deleteSuccess() {
    WorkSpaceEntity entity =
        WorkSpaceEntity.create("团队名", "team-code", null, null, userId, 50, null);

    when(workSpaceRepository.findById(1L)).thenReturn(Optional.of(entity));

    service.delete(1L);

    verify(workSpaceRepository).deleteById(1L);
    verify(workSpaceMemberRepository).deleteByWorkSpaceId(1L);
  }
}
