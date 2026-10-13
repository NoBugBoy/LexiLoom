package com.knowflow.application.user.service.impl;

import com.knowflow.application.enums.WorkSpaceRoleEnum;
import com.knowflow.application.user.api.WorkSpaceMemberQueryService;
import com.knowflow.application.user.api.dto.WorkSpaceMemberDTO;
import com.knowflow.application.user.mapper.WorkSpaceMemberMapper;
import com.knowflow.application.user.model.entity.QWorkSpaceMemberEntity;
import com.knowflow.application.user.model.entity.WorkSpaceMemberEntity;
import com.knowflow.application.user.model.request.WorkSpaceMemberPageRequest;
import com.knowflow.application.user.model.response.WorkSpaceMemberResponse;
import com.knowflow.application.user.repository.WorkSpaceMemberRepository;
import com.querydsl.core.BooleanBuilder;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 团队成员查询服务实现 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkSpaceMemberQueryServiceImpl implements WorkSpaceMemberQueryService {

  private final WorkSpaceMemberRepository workSpaceMemberRepository;
  private final WorkSpaceMemberMapper workSpaceMemberMapper;

  @Override
  public Optional<WorkSpaceMemberDTO> getMember(Long workSpaceId, Long userId) {
    return workSpaceMemberRepository
        .findByWorkSpaceIdAndUserId(workSpaceId, userId)
        .map(workSpaceMemberMapper::toDTO);
  }

  @Override
  public Page<WorkSpaceMemberResponse> pageMembers(
      Long workSpaceId, WorkSpaceMemberPageRequest request) {
    QWorkSpaceMemberEntity qMember = QWorkSpaceMemberEntity.workSpaceMemberEntity;
    BooleanBuilder builder = new BooleanBuilder();
    builder.and(qMember.workSpaceId.eq(workSpaceId));

    if (request.getRole() != null) {
      builder.and(qMember.role.eq(request.getRole()));
    }

    Page<WorkSpaceMemberEntity> page =
        workSpaceMemberRepository.findAll(builder, request.toPageable());
    return page.map(workSpaceMemberMapper::toResponse);
  }

  @Override
  public List<WorkSpaceMemberResponse> listMembers(Long workSpaceId) {
    return workSpaceMemberRepository.findByWorkSpaceId(workSpaceId).stream()
        .map(workSpaceMemberMapper::toResponse)
        .toList();
  }

  @Override
  public boolean isMember(Long workSpaceId, Long userId) {
    return workSpaceMemberRepository.existsByWorkSpaceIdAndUserId(workSpaceId, userId);
  }

  @Override
  public boolean hasRole(Long workSpaceId, Long userId, WorkSpaceRoleEnum requiredRole) {
    return workSpaceMemberRepository
        .findByWorkSpaceIdAndUserId(workSpaceId, userId)
        .map(member -> member.hasAtLeastRole(requiredRole))
        .orElse(false);
  }
}
