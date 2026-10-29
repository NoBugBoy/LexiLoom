package io.lexiloom.application.document.mapper;

import io.lexiloom.application.document.api.dto.DocVersionDTO;
import io.lexiloom.application.document.model.entity.DocVersionEntity;
import io.lexiloom.application.document.api.dto.DocumentStateEnum;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** 版本快照 MapStruct 映射器 */
@Mapper
public interface DocVersionMapper {

  @Mapping(
      target = "status",
      expression =
          "java(io.lexiloom.application.document.api.dto.DocumentStateEnum.PENDING_APPROVAL)")
  DocVersionDTO toDTO(DocVersionEntity entity);

  @Mapping(target = "status", source = "status")
  DocVersionDTO toDTO(DocVersionEntity entity, DocumentStateEnum status);

  java.util.List<DocVersionDTO> toDTOList(java.util.List<DocVersionEntity> entities);
}
