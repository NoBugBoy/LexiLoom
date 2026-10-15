package io.lexiloom.application.document.mapper;

import io.lexiloom.application.document.api.dto.DocCommentDTO;
import io.lexiloom.application.document.model.entity.DocCommentEntity;
import org.mapstruct.Mapper;

/** 评论实体 MapStruct 映射器 */
@Mapper
public interface DocCommentMapper {

  DocCommentDTO toDTO(DocCommentEntity entity);

  java.util.List<DocCommentDTO> toDTOList(java.util.List<DocCommentEntity> entities);
}
