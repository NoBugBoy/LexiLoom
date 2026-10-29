package io.lexiloom.application.user.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.lexiloom.application.enums.WorkSpaceRoleEnum;
import io.lexiloom.application.user.api.dto.WorkSpaceMemberDTO;
import io.lexiloom.application.user.mapper.WorkSpaceMemberMapper;
import io.lexiloom.application.user.model.entity.WorkSpaceMemberEntity;
import io.lexiloom.application.user.model.response.WorkSpaceMemberResponse;
import io.lexiloom.application.user.repository.WorkSpaceMemberRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class WorkSpaceMemberQueryServiceImplTest {

  @Mock private WorkSpaceMemberRepository workSpaceMemberRepository;
  @Mock private WorkSpaceMemberMapper workSpaceMemberMapper;
  @InjectMocks private WorkSpaceMemberQueryServiceImpl memberQueryService;

  @Test
  @DisplayName("getMember & getMemberResponse")
  void testGetMember() {
    WorkSpaceMemberEntity entity = WorkSpaceMemberEntity.create(10L, 2L, WorkSpaceRoleEnum.ADMIN);
    ReflectionTestUtils.setField(entity, "id", 1L);

    WorkSpaceMemberDTO dto = new WorkSpaceMemberDTO(1L, 10L, 2L, WorkSpaceRoleEnum.ADMIN, null);
    WorkSpaceMemberResponse response =
        new WorkSpaceMemberResponse(1L, 10L, 2L, WorkSpaceRoleEnum.ADMIN, null);

    when(workSpaceMemberRepository.findByWorkSpaceIdAndUserId(10L, 2L))
        .thenReturn(Optional.of(entity));
    when(workSpaceMemberMapper.toDTO(entity)).thenReturn(dto);
    when(workSpaceMemberMapper.toResponse(entity)).thenReturn(response);

    assertThat(memberQueryService.getMember(10L, 2L)).contains(dto);
    assertThat(memberQueryService.getMemberResponse(10L, 2L)).contains(response);
  }

  @Test
  @DisplayName("isMember & hasRole")
  void testMembershipAndRole() {
    when(workSpaceMemberRepository.existsByWorkSpaceIdAndUserId(10L, 2L)).thenReturn(true);
    assertThat(memberQueryService.isMember(10L, 2L)).isTrue();

    WorkSpaceMemberEntity entity = WorkSpaceMemberEntity.create(10L, 2L, WorkSpaceRoleEnum.ADMIN);
    when(workSpaceMemberRepository.findByWorkSpaceIdAndUserId(10L, 2L))
        .thenReturn(Optional.of(entity));

    assertThat(memberQueryService.hasRole(10L, 2L, WorkSpaceRoleEnum.MEMBER)).isTrue();
    assertThat(memberQueryService.hasRole(10L, 2L, WorkSpaceRoleEnum.OWNER)).isFalse();
  }
}
