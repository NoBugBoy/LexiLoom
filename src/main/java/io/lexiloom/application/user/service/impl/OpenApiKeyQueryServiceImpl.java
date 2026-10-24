package io.lexiloom.application.user.service.impl;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.common.security.LoginToken;
import io.lexiloom.application.enums.AdminRoleEnum;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.api.ApiKeyAuthApi;
import io.lexiloom.application.user.api.dto.OpenApiKeyDTO;
import io.lexiloom.application.user.mapper.OpenApiKeyMapper;
import io.lexiloom.application.user.model.entity.OpenApiKeyEntity;
import io.lexiloom.application.user.model.vo.OpenApiKeyVO;
import io.lexiloom.application.user.repository.OpenApiKeyRepository;
import io.lexiloom.application.user.service.AdminRoleService;
import io.lexiloom.application.user.service.OpenApiKeyQueryService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAPI 凭据只读查询服务实现类 (兼具 ApiKeyAuthApi 鉴权验证契约实现)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenApiKeyQueryServiceImpl implements OpenApiKeyQueryService, ApiKeyAuthApi {

  public static final String REDIS_OPENAPI_KEY_PREFIX = "openapi:key:";

  private final OpenApiKeyRepository openApiKeyRepository;
  private final OpenApiKeyMapper openApiKeyMapper;
  private final StringRedisTemplate stringRedisTemplate;
  private final ObjectMapper objectMapper;
  private final AdminRoleService adminRoleService;

  @Override
  @Transactional(readOnly = true)
  public Optional<LoginToken> verifyApiKey(String apiKey) {
    if (StrUtil.isBlank(apiKey)) {
      return Optional.empty();
    }
    return findCachedKeyInfo(apiKey)
        .map(
            openKey -> {
              List<AdminRoleEnum> roles = adminRoleService.findRolesByUserId(openKey.userId());
              return new LoginToken(openKey.userId(), roles);
            });
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<OpenApiKeyDTO> findOpenApiKey(String apiKey) {
    return findCachedKeyInfo(apiKey);
  }

  @Override
  @Transactional(readOnly = true)
  public List<OpenApiKeyVO> listMyKeys(Long userId) {
    if (userId == null) {
      return List.of();
    }
    return openApiKeyRepository.findByUserIdOrderByCreateTimeDesc(userId).stream()
        .map(openApiKeyMapper::toVO)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public OpenApiKeyVO getKeyDetail(Long userId, Long keyId) {
    if (userId == null) {
      throw BusinessException.unauthorized("未登录");
    }
    OpenApiKeyEntity entity =
        openApiKeyRepository
            .findById(keyId)
            .orElseThrow(() -> BusinessException.notFound("Key不存在: " + keyId));
    if (!Objects.equals(entity.getUserId(), userId)) {
      throw BusinessException.forbidden("无权访问该 Key");
    }
    return openApiKeyMapper.toVO(entity);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<OpenApiKeyDTO> findCachedKeyInfo(String apiKey) {
    if (StrUtil.isBlank(apiKey)) {
      return Optional.empty();
    }

    // 1. 优先查 Redis 快速缓存
    try {
      String cachedJson = stringRedisTemplate.opsForValue().get(REDIS_OPENAPI_KEY_PREFIX + apiKey);
      if (StrUtil.isNotBlank(cachedJson)) {
        OpenApiKeyDTO dto = objectMapper.readValue(cachedJson, OpenApiKeyDTO.class);
        if (dto != null) {
          asyncRecordUsage(apiKey);
          return Optional.of(dto);
        }
      }
    } catch (Exception e) {
      log.warn("【OpenApiKey】读取 Redis 缓存异常: {}", e.getMessage());
    }

    // 2. 查数据库
    Optional<OpenApiKeyEntity> entityOpt = openApiKeyRepository.findByApiKey(apiKey);
    if (entityOpt.isEmpty()) {
      return Optional.empty();
    }

    OpenApiKeyEntity entity = entityOpt.get();
    if (!entity.isUsable()) {
      return Optional.empty();
    }

    OpenApiKeyDTO dto = openApiKeyMapper.toDTO(entity);

    // 3. 回写 Redis
    try {
      String json = objectMapper.writeValueAsString(dto);
      stringRedisTemplate.opsForValue().set(REDIS_OPENAPI_KEY_PREFIX + apiKey, json, 7, TimeUnit.DAYS);
    } catch (Exception e) {
      log.warn("【OpenApiKey】回写 Redis 缓存异常: {}", e.getMessage());
    }

    asyncRecordUsage(apiKey);
    return Optional.of(dto);
  }

  /**
   * 采用虚拟线程异步更新最近使用时间与调用次数，完全避免阻塞业务调用线程
   */
  public void asyncRecordUsage(String apiKey) {
    Thread.ofVirtual()
        .name("openapi-key-usage-record")
        .start(
            () -> {
              try {
                openApiKeyRepository
                    .findByApiKey(apiKey)
                    .ifPresent(
                        entity -> {
                          LocalDateTime lastUsed = entity.getLastUsedTime();
                          if (lastUsed == null
                              || lastUsed.isBefore(LocalDateTime.now().minusSeconds(30))) {
                            entity.recordUsage();
                            openApiKeyRepository.save(entity);
                          }
                        });
              } catch (Exception e) {
                log.debug("【OpenApiKey】异步记录使用时间失败: {}", e.getMessage());
              }
            });
  }
}
