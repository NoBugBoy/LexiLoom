package io.lexiloom.application.user.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.enums.OpenApiKeyStatusEnum;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.api.dto.OpenApiKeyDTO;
import io.lexiloom.application.user.mapper.OpenApiKeyMapper;
import io.lexiloom.application.user.model.entity.OpenApiKeyEntity;
import io.lexiloom.application.user.model.request.CreateOpenApiKeyRequest;
import io.lexiloom.application.user.model.request.UpdateOpenApiKeyRequest;
import io.lexiloom.application.user.model.response.CreateOpenApiKeyResponse;
import io.lexiloom.application.user.model.vo.OpenApiKeyVO;
import io.lexiloom.application.user.repository.OpenApiKeyRepository;
import io.lexiloom.application.user.repository.UserRepository;
import io.lexiloom.application.user.service.OpenApiKeyCommandService;
import io.lexiloom.application.utils.DesensitizeUtils;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAPI 凭据指令服务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenApiKeyCommandServiceImpl implements OpenApiKeyCommandService {

  public static final String REDIS_OPENAPI_KEY_PREFIX = "openapi:key:";

  private final OpenApiKeyRepository openApiKeyRepository;
  private final UserRepository userRepository;
  private final OpenApiKeyMapper openApiKeyMapper;
  private final StringRedisTemplate stringRedisTemplate;
  private final ObjectMapper objectMapper;

  @Override
  @Transactional
  public CreateOpenApiKeyResponse createKey(Long userId, CreateOpenApiKeyRequest request) {
    if (userId == null) {
      throw BusinessException.unauthorized("缺少有效用户身份标识");
    }
    if (request == null || StrUtil.isBlank(request.name())) {
      throw BusinessException.badRequest("密钥名称不能为空");
    }
    if (openApiKeyRepository.existsByUserIdAndName(userId, request.name().trim())) {
      throw BusinessException.badRequest("已存在同名的 OpenAPI Key: " + request.name().trim());
    }

    String rawApiKey = "lx_live_" + IdUtil.fastSimpleUUID();
    String maskedKey = DesensitizeUtils.maskApiKey(rawApiKey);
    LocalDateTime expiresAt = calculateExpiresAt(request.expireDays());

    OpenApiKeyEntity entity =
        OpenApiKeyEntity.create(
            userId,
            request.name(),
            rawApiKey,
            maskedKey,
            request.scopes(),
            request.allowedWorkspaceIds(),
            expiresAt,
            request.llmProvider(),
            request.llmApiKey());

    entity = openApiKeyRepository.save(entity);

    // 缓存凭据信息至 Redis (TTL 与有效期同步或默认 7 天)
    cacheKeyInfo(rawApiKey, openApiKeyMapper.toDTO(entity));

    log.info(
        "【OpenApiKey】成功创建 OpenAPI Key: userId={}, keyId={}, name={}, maskedKey={}",
        userId,
        entity.getId(),
        entity.getName(),
        maskedKey);

    return new CreateOpenApiKeyResponse(openApiKeyMapper.toVO(entity), rawApiKey);
  }

  @Override
  @Transactional
  public OpenApiKeyVO updateKey(Long userId, Long keyId, UpdateOpenApiKeyRequest request) {
    OpenApiKeyEntity entity = findAndVerifyOwner(userId, keyId);

    LocalDateTime expiresAt =
        request.expireDays() != null
            ? calculateExpiresAt(request.expireDays())
            : entity.getExpiresAt();

    entity.updateConfig(
        request.name(),
        request.scopes(),
        request.allowedWorkspaceIds(),
        expiresAt,
        request.llmProvider(),
        request.llmApiKey());

    if (request.status() != null) {
      if (request.status() == OpenApiKeyStatusEnum.ENABLED) {
        entity.enable();
      } else {
        entity.disable();
      }
    }

    entity = openApiKeyRepository.save(entity);

    // 刷新或清理缓存
    if (entity.isUsable()) {
      cacheKeyInfo(entity.getApiKey(), openApiKeyMapper.toDTO(entity));
    } else {
      evictCache(entity.getApiKey());
    }

    return openApiKeyMapper.toVO(entity);
  }

  @Override
  @Transactional
  public CreateOpenApiKeyResponse rotateKey(Long userId, Long keyId) {
    OpenApiKeyEntity entity = findAndVerifyOwner(userId, keyId);

    String oldApiKey = entity.getApiKey();
    evictCache(oldApiKey);

    String newRawApiKey = "lx_live_" + IdUtil.fastSimpleUUID();
    String newMaskedKey = DesensitizeUtils.maskApiKey(newRawApiKey);

    entity.rotateKey(newRawApiKey, newMaskedKey);
    entity = openApiKeyRepository.save(entity);

    if (entity.isUsable()) {
      cacheKeyInfo(newRawApiKey, openApiKeyMapper.toDTO(entity));
    }

    log.info(
        "【OpenApiKey】成功轮转 OpenAPI Key: userId={}, keyId={}, newMaskedKey={}",
        userId,
        entity.getId(),
        newMaskedKey);

    return new CreateOpenApiKeyResponse(openApiKeyMapper.toVO(entity), newRawApiKey);
  }

  @Override
  @Transactional
  public void deleteKey(Long userId, Long keyId) {
    OpenApiKeyEntity entity = findAndVerifyOwner(userId, keyId);
    evictCache(entity.getApiKey());
    openApiKeyRepository.delete(entity);
    log.info("【OpenApiKey】已删除 OpenAPI Key: userId={}, keyId={}", userId, keyId);
  }

  @Override
  @Transactional
  public void toggleKeyStatus(Long userId, Long keyId, OpenApiKeyStatusEnum status) {
    OpenApiKeyEntity entity = findAndVerifyOwner(userId, keyId);
    if (status == OpenApiKeyStatusEnum.ENABLED) {
      entity.enable();
      cacheKeyInfo(entity.getApiKey(), openApiKeyMapper.toDTO(entity));
    } else {
      entity.disable();
      evictCache(entity.getApiKey());
    }
    openApiKeyRepository.save(entity);
  }

  private OpenApiKeyEntity findAndVerifyOwner(Long userId, Long keyId) {
    if (userId == null) {
      throw BusinessException.unauthorized("缺少有效用户身份标识");
    }
    if (keyId == null) {
      throw BusinessException.badRequest("Key ID 不能为空");
    }
    OpenApiKeyEntity entity =
        openApiKeyRepository
            .findById(keyId)
            .orElseThrow(() -> BusinessException.notFound("OpenAPI Key 不存在: " + keyId));
    if (!Objects.equals(entity.getUserId(), userId)) {
      throw BusinessException.forbidden("您无权操作该 OpenAPI Key");
    }
    return entity;
  }

  private LocalDateTime calculateExpiresAt(Integer expireDays) {
    if (expireDays == null || expireDays <= 0) {
      return null;
    }
    return LocalDateTime.now().plusDays(expireDays);
  }

  private void cacheKeyInfo(String apiKey, OpenApiKeyDTO dto) {
    try {
      String json = objectMapper.writeValueAsString(dto);
      stringRedisTemplate.opsForValue().set(REDIS_OPENAPI_KEY_PREFIX + apiKey, json, 7, TimeUnit.DAYS);
    } catch (Exception e) {
      log.warn("【OpenApiKey】写入 Redis 缓存失败: apiKey={}, err={}", StrUtil.sub(apiKey, 0, 10), e.getMessage());
    }
  }

  private void evictCache(String apiKey) {
    if (StrUtil.isBlank(apiKey)) {
      return;
    }
    try {
      stringRedisTemplate.delete(REDIS_OPENAPI_KEY_PREFIX + apiKey);
    } catch (Exception e) {
      log.warn("【OpenApiKey】清理 Redis 缓存失败: apiKey={}, err={}", StrUtil.sub(apiKey, 0, 10), e.getMessage());
    }
  }
}
