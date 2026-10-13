package com.knowflow.application.user.service.impl;

import com.knowflow.application.user.api.WorkSpaceQueryService;
import com.knowflow.application.user.api.dto.WorkSpaceDTO;
import com.knowflow.application.user.mapper.WorkSpaceMapper;
import com.knowflow.application.user.model.entity.QWorkSpaceEntity;
import com.knowflow.application.user.model.entity.WorkSpaceEntity;
import com.knowflow.application.user.model.entity.WorkSpaceMemberEntity;
import com.knowflow.application.user.model.request.WorkSpacePageRequest;
import com.knowflow.application.user.model.response.WorkSpaceResponse;
import com.knowflow.application.user.repository.WorkSpaceMemberRepository;
import com.knowflow.application.user.repository.WorkSpaceRepository;
import com.querydsl.core.BooleanBuilder;
import java.util.List;
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
public class WorkSpaceQueryServiceImpl implements WorkSpaceQueryService {

  private final WorkSpaceRepository workSpaceRepository;
  private final WorkSpaceMemberRepository workSpaceMemberRepository;
  private final WorkSpaceMapper workSpaceMapper;

  @Override
  public Optional<WorkSpaceDTO> getById(Long id) {
    return workSpaceRepository.findById(id).map(workSpaceMapper::toDTO);
  }

  @Override
  public Optional<WorkSpaceDTO> getByCode(String code) {
    if (!StringUtils.hasText(code)) {
      return Optional.empty();
    }
    return workSpaceRepository.findByCode(code.trim().toLowerCase()).map(workSpaceMapper::toDTO);
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
        .map(workSpaceMapper::toResponse)
        .toList();
  }
}
