package io.lexiloom.application.user.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lexiloom.application.enums.AiLlmProviderEnum;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.mapper.OpenApiKeyMapper;
import io.lexiloom.application.user.mapper.OpenApiKeyMapperImpl;
import io.lexiloom.application.user.model.entity.OpenApiKeyEntity;
import io.lexiloom.application.user.model.request.CreateOpenApiKeyRequest;
import io.lexiloom.application.user.model.response.CreateOpenApiKeyResponse;
import io.lexiloom.application.user.repository.OpenApiKeyRepository;
import io.lexiloom.application.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class OpenApiKeyCommandServiceImplTest {

  @Mock private OpenApiKeyRepository openApiKeyRepository;
  @Mock private UserRepository userRepository;
  @Mock private StringRedisTemplate stringRedisTemplate;
  @Mock private ValueOperations<String, String> valueOperations;

  private final OpenApiKeyMapper openApiKeyMapper = new OpenApiKeyMapperImpl();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private OpenApiKeyCommandServiceImpl service;

  @BeforeEach
  void setUp() {
    service =
        new OpenApiKeyCommandServiceImpl(
            openApiKeyRepository, userRepository, openApiKeyMapper, stringRedisTemplate, objectMapper);
  }

  @Test
  @DisplayName("createKey - 成功创建 Key 并生成明文凭证")
  void testCreateKey_success() {
    when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    when(openApiKeyRepository.existsByUserIdAndName(1L, "OA-Key")).thenReturn(false);
    when(openApiKeyRepository.save(any(OpenApiKeyEntity.class)))
        .thenAnswer(invocation -> {
          OpenApiKeyEntity entity = invocation.getArgument(0);
          ReflectionTestUtils.setField(entity, "id", 100L);
          return entity;
        });

    CreateOpenApiKeyRequest req =
        new CreateOpenApiKeyRequest("OA-Key", "kb:read,kb:search", "1,2", 30, AiLlmProviderEnum.QWEN, "sk-test-key");

    CreateOpenApiKeyResponse resp = service.createKey(1L, req);

    assertThat(resp).isNotNull();
    assertThat(resp.rawApiKey()).startsWith("lx_live_");
    assertThat(resp.keyInfo().name()).isEqualTo("OA-Key");
    assertThat(resp.keyInfo().llmProvider()).isEqualTo(AiLlmProviderEnum.QWEN);
    assertThat(resp.keyInfo().maskedLlmApiKey()).isNotBlank();
  }

  @Test
  @DisplayName("createKey - 重复名称抛出 400 异常")
  void testCreateKey_duplicateName() {
    when(openApiKeyRepository.existsByUserIdAndName(1L, "OA-Key")).thenReturn(true);
    CreateOpenApiKeyRequest req = new CreateOpenApiKeyRequest("OA-Key", null, null, null, null, null);

    assertThatThrownBy(() -> service.createKey(1L, req))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("已存在同名的 OpenAPI Key");
  }

  @Test
  @DisplayName("rotateKey - 成功轮转并使旧 Key 失效")
  void testRotateKey_success() {
    when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

    OpenApiKeyEntity entity =
        OpenApiKeyEntity.create(1L, "OA-Key", "lx_live_old", "lx_live_old_masked", null, null, null, null, null);
    ReflectionTestUtils.setField(entity, "id", 50L);

    when(openApiKeyRepository.findById(50L)).thenReturn(Optional.of(entity));
    when(openApiKeyRepository.save(any(OpenApiKeyEntity.class))).thenAnswer(i -> i.getArgument(0));

    CreateOpenApiKeyResponse resp = service.rotateKey(1L, 50L);

    assertThat(resp.rawApiKey()).isNotEqualTo("lx_live_old");
    assertThat(entity.getApiKey()).isEqualTo(resp.rawApiKey());
    verify(stringRedisTemplate).delete("openapi:key:lx_live_old");
  }

  @Test
  @DisplayName("deleteKey - 校验归属权并删除")
  void testDeleteKey_success() {
    OpenApiKeyEntity entity =
        OpenApiKeyEntity.create(1L, "OA-Key", "lx_live_123", "masked", null, null, null, null, null);
    when(openApiKeyRepository.findById(10L)).thenReturn(Optional.of(entity));

    service.deleteKey(1L, 10L);

    verify(openApiKeyRepository).delete(entity);
    verify(stringRedisTemplate).delete("openapi:key:lx_live_123");
  }

  @Test
  @DisplayName("deleteKey - 非本人操作抛出 403")
  void testDeleteKey_forbidden() {
    OpenApiKeyEntity entity =
        OpenApiKeyEntity.create(999L, "OA-Key", "lx_live_123", "masked", null, null, null, null, null);
    when(openApiKeyRepository.findById(10L)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.deleteKey(1L, 10L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("您无权操作该 OpenAPI Key");
  }
}
