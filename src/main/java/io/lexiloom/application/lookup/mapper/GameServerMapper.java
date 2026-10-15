package io.lexiloom.application.lookup.mapper;

import io.lexiloom.application.lookup.model.entity.GameServerEntity;
import org.mapstruct.Mapper;

@Mapper
public interface GameServerMapper {

  GameServerEntity toEntity(String serverName);
}
