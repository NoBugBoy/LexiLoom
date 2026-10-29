package io.lexiloom.application.user.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.lexiloom.application.enums.WorkSpaceStatusEnum;
import io.lexiloom.application.user.api.dto.WorkSpaceDTO;
import io.lexiloom.application.user.mapper.WorkSpaceMapper;
import io.lexiloom.application.user.model.entity.WorkSpaceEntity;
import io.lexiloom.application.user.model.entity.WorkSpaceMemberEntity;
import io.lexiloom.application.user.model.response.WorkSpaceResponse;
import io.lexiloom.application.user.repository.WorkSpaceMemberRepository;
import io.lexiloom.application.user.repository.WorkSpaceRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class WorkSpaceQueryServiceImplTest {

  @Mock private WorkSpaceRepository workSpaceRepository;
  @Mock private WorkSpaceMemberRepository workSpaceMemberRepository;
  @Mock private WorkSpaceMapper workSpaceMapper;
  @InjectMocks private WorkSpaceQueryServiceImpl queryService;

  @Test
  @DisplayName("getById & getResponseById")
  void testGetById() {
    WorkSpaceEntity entity = WorkSpaceEntity.create("Team WS", "ws", null, null, 1L, 10, null);
    ReflectionTestUtils.setField(entity, "id", 10L);

    WorkSpaceDTO dto = new WorkSpaceDTO(10L, "Team WS", null, "ws", null, 1L, 10, WorkSpaceStatusEnum.NORMAL, null, null, null, null);
    WorkSpaceResponse response =
        new WorkSpaceResponse(10L, "Team WS", null, "ws", null, 1L, 10, WorkSpaceStatusEnum.NORMAL, null, null, null, null);

    when(workSpaceRepository.findById(10L)).thenReturn(Optional.of(entity));
    when(workSpaceMapper.toDTO(entity)).thenReturn(dto);
    when(workSpaceMapper.toResponse(entity)).thenReturn(response);

    assertThat(queryService.getById(10L)).contains(dto);
    assertThat(queryService.getResponseById(10L)).contains(response);
  }

  @Test
  @DisplayName("getByCode & getResponseByCode")
  void testGetByCode() {
    WorkSpaceEntity entity = WorkSpaceEntity.create("Team", "team-code", null, null, 1L, 10, null);
    WorkSpaceResponse response =
        new WorkSpaceResponse(10L, "Team", null, "team-code", null, 1L, 10, WorkSpaceStatusEnum.NORMAL, null, null, null, null);

    when(workSpaceRepository.findByCode("team-code")).thenReturn(Optional.of(entity));
    when(workSpaceMapper.toResponse(entity)).thenReturn(response);

    assertThat(queryService.getByCode("")).isEmpty();
    assertThat(queryService.getResponseByCode("team-code")).contains(response);
  }

  @Test
  @DisplayName("listMyWorkSpaces 过滤已解散 DISBANDED 的团队")
  void testListMyWorkSpaces_filterDisbanded() {
    WorkSpaceMemberEntity member = WorkSpaceMemberEntity.create(10L, 1L, io.lexiloom.application.enums.WorkSpaceRoleEnum.MEMBER);
    when(workSpaceMemberRepository.findByUserId(1L)).thenReturn(List.of(member));

    WorkSpaceEntity activeWs = WorkSpaceEntity.create("Active", "ws", null, null, 1L, 10, null);
    ReflectionTestUtils.setField(activeWs, "id", 10L);

    when(workSpaceRepository.findAllById(List.of(10L))).thenReturn(List.of(activeWs));
    WorkSpaceResponse response =
        new WorkSpaceResponse(10L, "Active", null, "ws", null, 1L, 10, WorkSpaceStatusEnum.NORMAL, null, null, null, null);
    when(workSpaceMapper.toResponse(activeWs)).thenReturn(response);

    List<WorkSpaceResponse> list = queryService.listMyWorkSpaces(1L);
    assertThat(list).hasSize(1);

    // When status is DISBANDED
    ReflectionTestUtils.setField(activeWs, "status", WorkSpaceStatusEnum.DISBANDED);
    List<WorkSpaceResponse> emptyList = queryService.listMyWorkSpaces(1L);
    assertThat(emptyList).isEmpty();
  }
}
