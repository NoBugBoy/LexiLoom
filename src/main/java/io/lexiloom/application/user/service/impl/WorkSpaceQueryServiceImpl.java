package io.lexiloom.application.user.service.impl;

import com.querydsl.core.BooleanBuilder;
import io.lexiloom.application.enums.WorkSpaceStatusEnum;
import io.lexiloom.application.user.api.WorkSpaceQueryService;
import io.lexiloom.application.user.api.dto.WorkSpaceDTO;
import io.lexiloom.application.user.mapper.WorkSpaceMapper;
import io.lexiloom.application.user.model.entity.QWorkSpaceEntity;
import io.lexiloom.application.user.model.entity.WorkSpaceEntity;
import io.lexiloom.application.user.model.entity.WorkSpaceMemberEntity;
import io.lexiloom.application.user.model.request.WorkSpacePageRequest;
import io.lexiloom.application.user.model.response.WorkSpaceResponse;
import io.lexiloom.application.user.repository.WorkSpaceMemberRepository;
import io.lexiloom.application.user.repository.WorkSpaceRepository;
import io.lexiloom.application.user.service.WorkSpaceViewService;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 团队查询服务实现 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkSpaceQueryServiceImpl implements WorkSpaceQueryService, WorkSpaceViewService {

  private final WorkSpaceRepository workSpaceRepository;
  private final WorkSpaceMemberRepository workSpaceMemberRepository;
  private final WorkSpaceMapper workSpaceMapper;

  @Override
  public Optional<WorkSpaceDTO> getById(Long id) {
    return workSpaceRepository.findById(id).map(workSpaceMapper::toDTO);
  }

  @Override
  public Optional<WorkSpaceResponse> getResponseById(Long id) {
    return workSpaceRepository.findById(id).map(workSpaceMapper::toResponse);
  }

  @Override
  public Optional<WorkSpaceDTO> getByCode(String code) {
    if (!StringUtils.hasText(code)) {
      return Optional.empty();
    }
    return workSpaceRepository.findByCode(code.trim().toLowerCase()).map(workSpaceMapper::toDTO);
  }

  @Override
  public Optional<WorkSpaceResponse> getResponseByCode(String code) {
    if (!StringUtils.hasText(code)) {
      return Optional.empty();
    }
    return workSpaceRepository.findByCode(code.trim().toLowerCase()).map(workSpaceMapper::toResponse);
  }

  @Override
  public Page<WorkSpaceResponse> page(WorkSpacePageRequest request) {
    QWorkSpaceEntity qWorkSpace = QWorkSpaceEntity.workSpaceEntity;
    BooleanBuilder builder = new BooleanBuilder();

    if (StringUtils.hasText(request.getKeyword())) {
      String keyword = request.getKeyword().trim();
      builder.and(
          qWorkSpace
              .name
              .containsIgnoreCase(keyword)
              .or(qWorkSpace.code.containsIgnoreCase(keyword)));
    }
    if (request.getStatus() != null) {
      builder.and(qWorkSpace.status.eq(request.getStatus()));
    }
    if (request.getVisibility() != null) {
      builder.and(qWorkSpace.visibility.eq(request.getVisibility()));
    }

    Page<WorkSpaceEntity> page =
        builder.getValue() == null
            ? workSpaceRepository.findAll(request.toPageable())
            : workSpaceRepository.findAll(builder, request.toPageable());

    return page.map(workSpaceMapper::toResponse);
  }

  @Override
  public List<WorkSpaceResponse> listMyWorkSpaces(Long userId) {
    List<Long> workSpaceIds =
        workSpaceMemberRepository.findByUserId(userId).stream()
            .map(WorkSpaceMemberEntity::getWorkSpaceId)
            .distinct()
            .toList();

    if (workSpaceIds.isEmpty()) {
      return List.of();
    }

    return workSpaceRepository.findAllById(workSpaceIds).stream()
        .filter(ws -> ws.getStatus() != WorkSpaceStatusEnum.DISBANDED)
        .map(workSpaceMapper::toResponse)
        .toList();
  }

  @Override
  public List<Long> listChildWorkspaceIds(Long parentWorkspaceId) {
    if (parentWorkspaceId == null) {
      return List.of();
    }
    return workSpaceRepository.findByParentIdAndChildAssocEnabledTrue(parentWorkspaceId).stream()
        .map(WorkSpaceEntity::getId)
        .filter(Objects::nonNull)
        .toList();
  }

  @Override
  public java.util.Map<Long, String> getWorkspaceNames(java.util.Collection<Long> workspaceIds) {
    if (workspaceIds == null || workspaceIds.isEmpty()) {
      return java.util.Map.of();
    }
    return workSpaceRepository.findAllById(workspaceIds).stream()
        .collect(
            java.util.stream.Collectors.toMap(
                WorkSpaceEntity::getId, WorkSpaceEntity::getName, (a, b) -> a));
  }

  @Override
  public List<WorkSpaceDTO> listAllWorkspaces() {
    return workSpaceRepository.findAll().stream().map(workSpaceMapper::toDTO).toList();
  }

  @Override
  public List<WorkSpaceDTO> listMyWorkspaceDTOs(Long userId) {
    if (userId == null) {
      return List.of();
    }
    List<Long> workSpaceIds =
        workSpaceMemberRepository.findByUserId(userId).stream()
            .map(WorkSpaceMemberEntity::getWorkSpaceId)
            .distinct()
            .toList();

    if (workSpaceIds.isEmpty()) {
      return List.of();
    }

    return workSpaceRepository.findAllById(workSpaceIds).stream()
        .filter(ws -> ws.getStatus() != WorkSpaceStatusEnum.DISBANDED)
        .map(workSpaceMapper::toDTO)
        .toList();
  }
}
