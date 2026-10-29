package io.lexiloom.application.user.service.impl;

import com.querydsl.core.BooleanBuilder;
import io.lexiloom.application.enums.WorkSpaceRoleEnum;
import io.lexiloom.application.user.api.WorkSpaceMemberQueryService;
import io.lexiloom.application.user.api.dto.WorkSpaceMemberDTO;
import io.lexiloom.application.user.mapper.WorkSpaceMemberMapper;
import io.lexiloom.application.user.model.entity.QWorkSpaceMemberEntity;
import io.lexiloom.application.user.model.entity.WorkSpaceMemberEntity;
import io.lexiloom.application.user.model.request.WorkSpaceMemberPageRequest;
import io.lexiloom.application.user.model.response.WorkSpaceMemberResponse;
import io.lexiloom.application.user.repository.WorkSpaceMemberRepository;
import io.lexiloom.application.user.service.WorkSpaceMemberViewService;
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
public class WorkSpaceMemberQueryServiceImpl
    implements WorkSpaceMemberQueryService, WorkSpaceMemberViewService {

  private final WorkSpaceMemberRepository workSpaceMemberRepository;
  private final WorkSpaceMemberMapper workSpaceMemberMapper;

  @Override
  public Optional<WorkSpaceMemberDTO> getMember(Long workSpaceId, Long userId) {
    return workSpaceMemberRepository
        .findByWorkSpaceIdAndUserId(workSpaceId, userId)
        .map(workSpaceMemberMapper::toDTO);
  }

  @Override
  public Optional<WorkSpaceMemberResponse> getMemberResponse(Long workSpaceId, Long userId) {
    return workSpaceMemberRepository
        .findByWorkSpaceIdAndUserId(workSpaceId, userId)
        .map(workSpaceMemberMapper::toResponse);
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

  @Override
  public long countMembers(Long workSpaceId) {
    return workSpaceMemberRepository.countByWorkSpaceId(workSpaceId);
  }

  @Override
  public long countMembersByRole(Long workSpaceId, WorkSpaceRoleEnum role) {
    return workSpaceMemberRepository.countByWorkSpaceIdAndRole(workSpaceId, role);
  }
}
