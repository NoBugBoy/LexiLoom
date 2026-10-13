package com.knowflow.application.document.mapper;

import com.knowflow.application.document.api.dto.DocCommentDTO;
import com.knowflow.application.document.model.entity.DocCommentEntity;
import org.mapstruct.Mapper;

/** 评论实体 MapStruct 映射器 */
@Mapper
public interface DocCommentMapper {

  DocCommentDTO toDTO(DocCommentEntity entity);

  java.util.List<DocCommentDTO> toDTOList(java.util.List<DocCommentEntity> entities);
}
