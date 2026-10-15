package io.lexiloom.application.lookup.mapper;

import io.lexiloom.application.lookup.model.entity.SectEntity;
import org.mapstruct.Mapper;

@Mapper
public interface SectMapper {

  SectEntity toSectEntity(String sectName);
}
