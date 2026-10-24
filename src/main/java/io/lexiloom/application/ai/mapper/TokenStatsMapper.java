package io.lexiloom.application.ai.mapper;

import io.lexiloom.application.ai.api.dto.TokenUsageStatsDTO;
import io.lexiloom.application.ai.model.response.TokenUsageStatsResponse;
import org.mapstruct.Mapper;

/** Token 消耗统计数据映射器 (MapStruct) */
@Mapper(componentModel = "spring")
public interface TokenStatsMapper {

  TokenUsageStatsResponse toResponse(TokenUsageStatsDTO dto);

  TokenUsageStatsDTO toDTO(TokenUsageStatsResponse response);
}
