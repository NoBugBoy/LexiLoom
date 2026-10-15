package io.lexiloom.application.user.mapper;

import io.lexiloom.application.user.api.dto.WorkSpaceMemberDTO;
import io.lexiloom.application.user.model.entity.WorkSpaceMemberEntity;
import io.lexiloom.application.user.model.response.WorkSpaceMemberResponse;
import org.mapstruct.Mapper;

@Mapper
public interface WorkSpaceMemberMapper {

  WorkSpaceMemberDTO toDTO(WorkSpaceMemberEntity entity);

  WorkSpaceMemberResponse toResponse(WorkSpaceMemberEntity entity);

  WorkSpaceMemberResponse toResponse(WorkSpaceMemberDTO dto);
}
