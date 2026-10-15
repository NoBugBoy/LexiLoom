package io.lexiloom.application.document.mapper;

import io.lexiloom.application.document.api.dto.DocCategoryDTO;
import io.lexiloom.application.document.model.entity.DocCategoryEntity;
import org.mapstruct.Mapper;

/** 分类目录 MapStruct 映射器 */
@Mapper
public interface DocCategoryMapper {

  DocCategoryDTO toDTO(DocCategoryEntity entity);
}
