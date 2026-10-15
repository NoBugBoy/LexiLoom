package io.lexiloom.application.document.mapper;

import io.lexiloom.application.document.api.dto.DocSourceFileDTO;
import io.lexiloom.application.document.model.entity.DocSourceFileEntity;
import org.mapstruct.Mapper;

/** 来源文件 MapStruct 映射器 */
@Mapper
public interface DocSourceFileMapper {

  DocSourceFileDTO toDTO(DocSourceFileEntity entity);
}
