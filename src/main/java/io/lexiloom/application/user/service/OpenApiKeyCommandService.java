package io.lexiloom.application.user.service;

import io.lexiloom.application.enums.OpenApiKeyStatusEnum;
import io.lexiloom.application.user.model.request.CreateOpenApiKeyRequest;
import io.lexiloom.application.user.model.request.UpdateOpenApiKeyRequest;
import io.lexiloom.application.user.model.response.CreateOpenApiKeyResponse;
import io.lexiloom.application.user.model.vo.OpenApiKeyVO;

/**
 * OpenAPI 凭据指令服务 (Command Service)
 */
public interface OpenApiKeyCommandService {

  /**
   * 创建新的 OpenAPI 凭据
   *
   * @param userId 归属用户 ID
   * @param request 创建请求参数
   * @return 创建结果 (包含仅展示一次的原始完整明文 Key)
   */
  CreateOpenApiKeyResponse createKey(Long userId, CreateOpenApiKeyRequest request);

  /**
   * 更新已有凭据配置
   *
   * @param userId 操作用户 ID
   * @param keyId 凭据主键 ID
   * @param request 更新请求
   * @return 更新后的凭据视图
   */
  OpenApiKeyVO updateKey(Long userId, Long keyId, UpdateOpenApiKeyRequest request);

  /**
   * 重新生成轮转凭据 Key
   *
   * @param userId 操作用户 ID
   * @param keyId 凭据主键 ID
   * @return 轮转后的凭据视图
   */
  CreateOpenApiKeyResponse rotateKey(Long userId, Long keyId);

  /**
   * 删除指定凭据
   *
   * @param userId 操作用户 ID
   * @param keyId 凭据主键 ID
   */
  void deleteKey(Long userId, Long keyId);

  /**
   * 切换凭据启用/禁用状态
   *
   * @param userId 操作用户 ID
   * @param keyId 凭据主键 ID
   * @param status 目标状态
   */
  void toggleKeyStatus(Long userId, Long keyId, OpenApiKeyStatusEnum status);
}
