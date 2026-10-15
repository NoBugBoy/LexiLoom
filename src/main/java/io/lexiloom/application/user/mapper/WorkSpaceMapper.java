package io.lexiloom.application.user.mapper;

import io.lexiloom.application.user.api.dto.WorkSpaceDTO;
import io.lexiloom.application.user.model.entity.WorkSpaceEntity;
import io.lexiloom.application.user.model.response.WorkSpaceResponse;
import org.mapstruct.Mapper;

@Mapper
public interface WorkSpaceMapper {

  WorkSpaceDTO toDTO(WorkSpaceEntity entity);

  WorkSpaceResponse toResponse(WorkSpaceEntity entity);

  WorkSpaceResponse toResponse(WorkSpaceDTO dto);
}
