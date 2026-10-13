package com.knowflow.application.document.mapper;

import com.knowflow.application.document.api.dto.DocVersionDTO;
import com.knowflow.application.document.model.entity.DocVersionEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** 版本快照 MapStruct 映射器 */
@Mapper
public interface DocVersionMapper {

  @Mapping(
      target = "status",
      expression =
          "java(com.knowflow.application.document.statemachine.DocumentStateEnum.PENDING_APPROVAL)")
  DocVersionDTO toDTO(DocVersionEntity entity);

  @Mapping(target = "status", source = "status")
  DocVersionDTO toDTO(
      DocVersionEntity entity,
      com.knowflow.application.document.statemachine.DocumentStateEnum status);

  java.util.List<DocVersionDTO> toDTOList(java.util.List<DocVersionEntity> entities);
}
