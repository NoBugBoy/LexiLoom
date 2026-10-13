package com.knowflow.application.document.mapper;

import com.knowflow.application.document.api.dto.DocCategoryDTO;
import com.knowflow.application.document.model.entity.DocCategoryEntity;
import org.mapstruct.Mapper;

/** 分类目录 MapStruct 映射器 */
@Mapper
public interface DocCategoryMapper {

  DocCategoryDTO toDTO(DocCategoryEntity entity);
}
