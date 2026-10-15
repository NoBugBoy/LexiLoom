package io.lexiloom.application.user.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.lexiloom.application.common.LoginUserAuthentication;
import io.lexiloom.application.enums.WorkSpaceRoleEnum;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.model.entity.WorkSpaceEntity;
import io.lexiloom.application.user.model.entity.WorkSpaceMemberEntity;
import io.lexiloom.application.user.model.request.WorkSpaceMemberAddRequest;
import io.lexiloom.application.user.repository.WorkSpaceMemberRepository;
import io.lexiloom.application.user.repository.WorkSpaceRepository;
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
class WorkSpaceMemberCommandServiceImplTest {

  @Mock private WorkSpaceRepository workSpaceRepository;
  @Mock private WorkSpaceMemberRepository workSpaceMemberRepository;

  @InjectMocks private WorkSpaceMemberCommandServiceImpl service;

  private final Long currentUserId = 100L;
  private final Long workSpaceId = 1L;

  @BeforeEach
  void setUp() {
    SecurityContextHolder.getContext()
        .setAuthentication(new LoginUserAuthentication(currentUserId, Collections.emptyList()));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("添加成员成功")
  void addMemberSuccess() {
    Long targetUserId = 200L;
    WorkSpaceEntity workSpace =
        WorkSpaceEntity.create("团队", "team", null, null, currentUserId, 10, null);

    WorkSpaceMemberEntity currentMember =
        WorkSpaceMemberEntity.create(workSpaceId, currentUserId, WorkSpaceRoleEnum.OWNER);

    when(workSpaceRepository.findById(workSpaceId)).thenReturn(Optional.of(workSpace));
    when(workSpaceMemberRepository.findByWorkSpaceIdAndUserId(workSpaceId, currentUserId))
        .thenReturn(Optional.of(currentMember));
    when(workSpaceMemberRepository.countByWorkSpaceId(workSpaceId)).thenReturn(5L);
    when(workSpaceMemberRepository.existsByWorkSpaceIdAndUserId(workSpaceId, targetUserId))
        .thenReturn(false);

    WorkSpaceMemberEntity savedMember =
        WorkSpaceMemberEntity.create(workSpaceId, targetUserId, WorkSpaceRoleEnum.MEMBER);
    ReflectionTestUtils.setField(savedMember, "id", 10L);
    when(workSpaceMemberRepository.save(any(WorkSpaceMemberEntity.class))).thenReturn(savedMember);

    WorkSpaceMemberAddRequest request =
        new WorkSpaceMemberAddRequest(targetUserId, WorkSpaceRoleEnum.MEMBER);

    Long memberId = service.addMember(workSpaceId, request);

    assertThat(memberId).isEqualTo(10L);
    verify(workSpaceMemberRepository).save(any(WorkSpaceMemberEntity.class));
  }

  @Test
  @DisplayName("添加成员 - 超出人数上限抛出异常")
  void addMemberExceedsLimitThrows() {
    Long targetUserId = 200L;
    WorkSpaceEntity workSpace =
        WorkSpaceEntity.create("团队", "team", null, null, currentUserId, 5, null);

    WorkSpaceMemberEntity currentMember =
        WorkSpaceMemberEntity.create(workSpaceId, currentUserId, WorkSpaceRoleEnum.OWNER);

    when(workSpaceRepository.findById(workSpaceId)).thenReturn(Optional.of(workSpace));
    when(workSpaceMemberRepository.findByWorkSpaceIdAndUserId(workSpaceId, currentUserId))
        .thenReturn(Optional.of(currentMember));
    when(workSpaceMemberRepository.countByWorkSpaceId(workSpaceId)).thenReturn(5L);

    WorkSpaceMemberAddRequest request =
        new WorkSpaceMemberAddRequest(targetUserId, WorkSpaceRoleEnum.MEMBER);

    assertThatThrownBy(() -> service.addMember(workSpaceId, request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("已达到团队人数上限");
  }

  @Test
  @DisplayName("修改成员角色成功")
  void updateMemberRoleSuccess() {
    Long targetUserId = 200L;
    WorkSpaceEntity workSpace =
        WorkSpaceEntity.create("团队", "team", null, null, currentUserId, 10, null);

    WorkSpaceMemberEntity operator =
        WorkSpaceMemberEntity.create(workSpaceId, currentUserId, WorkSpaceRoleEnum.OWNER);
    WorkSpaceMemberEntity target =
        WorkSpaceMemberEntity.create(workSpaceId, targetUserId, WorkSpaceRoleEnum.MEMBER);

    when(workSpaceRepository.findById(workSpaceId)).thenReturn(Optional.of(workSpace));
    when(workSpaceMemberRepository.findByWorkSpaceIdAndUserId(workSpaceId, currentUserId))
        .thenReturn(Optional.of(operator));
    when(workSpaceMemberRepository.findByWorkSpaceIdAndUserId(workSpaceId, targetUserId))
        .thenReturn(Optional.of(target));

    service.updateMemberRole(workSpaceId, targetUserId, WorkSpaceRoleEnum.ADMIN);

    assertThat(target.getRole()).isEqualTo(WorkSpaceRoleEnum.ADMIN);
    verify(workSpaceMemberRepository).save(target);
  }

  @Test
  @DisplayName("移除成员成功")
  void removeMemberSuccess() {
    Long targetUserId = 200L;
    WorkSpaceEntity workSpace =
        WorkSpaceEntity.create("团队", "team", null, null, currentUserId, 10, null);

    WorkSpaceMemberEntity operator =
        WorkSpaceMemberEntity.create(workSpaceId, currentUserId, WorkSpaceRoleEnum.OWNER);
    WorkSpaceMemberEntity target =
        WorkSpaceMemberEntity.create(workSpaceId, targetUserId, WorkSpaceRoleEnum.MEMBER);

    when(workSpaceRepository.findById(workSpaceId)).thenReturn(Optional.of(workSpace));
    when(workSpaceMemberRepository.findByWorkSpaceIdAndUserId(workSpaceId, currentUserId))
        .thenReturn(Optional.of(operator));
    when(workSpaceMemberRepository.findByWorkSpaceIdAndUserId(workSpaceId, targetUserId))
        .thenReturn(Optional.of(target));

    service.removeMember(workSpaceId, targetUserId);

    verify(workSpaceMemberRepository).deleteByWorkSpaceIdAndUserId(workSpaceId, targetUserId);
  }

  @Test
  @DisplayName("移除成员 - 不能移除OWNER")
  void removeMemberCannotRemoveOwner() {
    WorkSpaceEntity workSpace =
        WorkSpaceEntity.create("团队", "team", null, null, currentUserId, 10, null);

    WorkSpaceMemberEntity operator =
        WorkSpaceMemberEntity.create(workSpaceId, currentUserId, WorkSpaceRoleEnum.OWNER);
    WorkSpaceMemberEntity targetOwner =
        WorkSpaceMemberEntity.create(workSpaceId, currentUserId, WorkSpaceRoleEnum.OWNER);

    when(workSpaceRepository.findById(workSpaceId)).thenReturn(Optional.of(workSpace));
    when(workSpaceMemberRepository.findByWorkSpaceIdAndUserId(workSpaceId, currentUserId))
        .thenReturn(Optional.of(operator));

    assertThatThrownBy(() -> service.removeMember(workSpaceId, currentUserId))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不可移除团队所有者");
  }

  @Test
  @DisplayName("主动退出团队成功")
  void leaveWorkSpaceSuccess() {
    WorkSpaceEntity workSpace = WorkSpaceEntity.create("团队", "team", null, null, 999L, 10, null);

    WorkSpaceMemberEntity member =
        WorkSpaceMemberEntity.create(workSpaceId, currentUserId, WorkSpaceRoleEnum.MEMBER);

    when(workSpaceRepository.findById(workSpaceId)).thenReturn(Optional.of(workSpace));
    when(workSpaceMemberRepository.findByWorkSpaceIdAndUserId(workSpaceId, currentUserId))
        .thenReturn(Optional.of(member));

    service.leaveWorkSpace(workSpaceId);

    verify(workSpaceMemberRepository).deleteByWorkSpaceIdAndUserId(workSpaceId, currentUserId);
  }

  @Test
  @DisplayName("所有者不可直接退出团队")
  void leaveWorkSpaceOwnerCannotLeave() {
    WorkSpaceEntity workSpace =
        WorkSpaceEntity.create("团队", "team", null, null, currentUserId, 10, null);

    WorkSpaceMemberEntity member =
        WorkSpaceMemberEntity.create(workSpaceId, currentUserId, WorkSpaceRoleEnum.OWNER);

    when(workSpaceRepository.findById(workSpaceId)).thenReturn(Optional.of(workSpace));
    when(workSpaceMemberRepository.findByWorkSpaceIdAndUserId(workSpaceId, currentUserId))
        .thenReturn(Optional.of(member));

    assertThatThrownBy(() -> service.leaveWorkSpace(workSpaceId))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("团队所有者不能直接退出团队");
  }
}
