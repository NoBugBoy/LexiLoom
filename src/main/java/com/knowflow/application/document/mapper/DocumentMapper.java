package com.knowflow.application.document.mapper;

import com.knowflow.application.document.api.dto.DocumentDTO;
import com.knowflow.application.document.model.entity.DocumentEntity;
import org.mapstruct.Mapper;

/** 文档实体 MapStruct 映射器 */
@Mapper
public interface DocumentMapper {

  DocumentDTO toDTO(DocumentEntity entity);

  java.util.List<DocumentDTO> toDTOList(java.util.List<DocumentEntity> entities);
}
