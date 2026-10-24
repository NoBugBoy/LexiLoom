package io.lexiloom.application.user.service;

import io.lexiloom.application.user.api.dto.OpenApiKeyDTO;
import io.lexiloom.application.user.model.vo.OpenApiKeyVO;
import java.util.List;
import java.util.Optional;

/**
 * OpenAPI 凭据只读查询服务 (Query Service)
 */
public interface OpenApiKeyQueryService {

  /**
   * 查询当前用户拥有的所有 OpenAPI Key 列表
   *
   * @param userId 用户主键 ID
   * @return Key 视图列表
   */
  List<OpenApiKeyVO> listMyKeys(Long userId);

  /**
   * 获取指定凭据详情
   *
   * @param userId 用户主键 ID
   * @param keyId 凭据主键 ID
   * @return Key 视图
   */
  OpenApiKeyVO getKeyDetail(Long userId, Long keyId);

  /**
   * 根据 API Key 字符串快速获取其凭据上下文信息 (带二级缓存支持)
   *
   * @param apiKey 原始 API Key 串
   * @return 凭据上下文 DTO
   */
  Optional<OpenApiKeyDTO> findCachedKeyInfo(String apiKey);
}
