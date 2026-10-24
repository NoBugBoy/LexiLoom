package io.lexiloom.application.user.mapper;

import io.lexiloom.application.user.api.dto.OpenApiKeyDTO;
import io.lexiloom.application.user.model.entity.OpenApiKeyEntity;
import io.lexiloom.application.user.model.vo.OpenApiKeyVO;
import io.lexiloom.application.utils.DesensitizeUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/**
 * OpenAPI 凭据实体、VO 与 DTO 转换映射器
 */
@Mapper(componentModel = "spring")
public interface OpenApiKeyMapper {

  @Mapping(target = "maskedLlmApiKey", source = "llmApiKey", qualifiedByName = "maskApiKey")
  OpenApiKeyVO toVO(OpenApiKeyEntity entity);

  OpenApiKeyDTO toDTO(OpenApiKeyEntity entity);

  @Named("maskApiKey")
  default String maskApiKey(String rawKey) {
    return DesensitizeUtils.maskApiKey(rawKey);
  }
}
