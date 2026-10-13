package com.knowflow.application.document.mapper;

import com.knowflow.application.document.api.dto.DocSourceFileDTO;
import com.knowflow.application.document.model.entity.DocSourceFileEntity;
import org.mapstruct.Mapper;

/** 来源文件 MapStruct 映射器 */
@Mapper
public interface DocSourceFileMapper {

  DocSourceFileDTO toDTO(DocSourceFileEntity entity);
}
