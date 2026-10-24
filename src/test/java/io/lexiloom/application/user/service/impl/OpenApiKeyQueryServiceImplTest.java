package io.lexiloom.application.user.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.lexiloom.application.common.security.LoginToken;
import io.lexiloom.application.enums.AdminRoleEnum;
import io.lexiloom.application.enums.AiLlmProviderEnum;
import io.lexiloom.application.user.api.dto.OpenApiKeyDTO;
import io.lexiloom.application.user.mapper.OpenApiKeyMapper;
import io.lexiloom.application.user.mapper.OpenApiKeyMapperImpl;
import io.lexiloom.application.user.model.entity.OpenApiKeyEntity;
import io.lexiloom.application.user.model.vo.OpenApiKeyVO;
import io.lexiloom.application.user.repository.OpenApiKeyRepository;
import io.lexiloom.application.user.service.AdminRoleService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class OpenApiKeyQueryServiceImplTest {

  @Mock private OpenApiKeyRepository openApiKeyRepository;
  @Mock private StringRedisTemplate stringRedisTemplate;
  @Mock private ValueOperations<String, String> valueOperations;
  @Mock private AdminRoleService adminRoleService;

  private final OpenApiKeyMapper openApiKeyMapper = new OpenApiKeyMapperImpl();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private OpenApiKeyQueryServiceImpl service;

  @BeforeEach
  void setUp() {
    service =
        new OpenApiKeyQueryServiceImpl(
            openApiKeyRepository,
            openApiKeyMapper,
            stringRedisTemplate,
            objectMapper,
            adminRoleService);
  }

  @Test
  @DisplayName("listMyKeys - 成功查询用户拥有的 Key 列表")
  void testListMyKeys() {
    OpenApiKeyEntity entity =
        OpenApiKeyEntity.create(1L, "Key-1", "lx_live_1", "masked_1", null, null, null, null, null);
    when(openApiKeyRepository.findByUserIdOrderByCreateTimeDesc(1L)).thenReturn(List.of(entity));

    List<OpenApiKeyVO> list = service.listMyKeys(1L);
    assertThat(list).hasSize(1);
    assertThat(list.getFirst().name()).isEqualTo("Key-1");
  }

  @Test
  @DisplayName("findCachedKeyInfo - 优先从 Redis 缓存命中")
  void testFindCachedKeyInfo_hitCache() throws Exception {
    when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

    OpenApiKeyDTO cached =
        new OpenApiKeyDTO(
            10L, 1L, "Key-Cached", "masked", "kb:read", null, null, null, AiLlmProviderEnum.NONE, null);
    String json = objectMapper.writeValueAsString(cached);
    when(valueOperations.get("openapi:key:lx_live_cache")).thenReturn(json);

    Optional<OpenApiKeyDTO> result = service.findCachedKeyInfo("lx_live_cache");
    assertThat(result).isPresent();
    assertThat(result.get().name()).isEqualTo("Key-Cached");
  }

  @Test
  @DisplayName("findCachedKeyInfo - 缓存未命中时从数据库查询并回填")
  void testFindCachedKeyInfo_fromDb() {
    when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("openapi:key:lx_live_db")).thenReturn(null);

    OpenApiKeyEntity entity =
        OpenApiKeyEntity.create(1L, "Key-Db", "lx_live_db", "masked", null, null, null, null, null);
    when(openApiKeyRepository.findByApiKey("lx_live_db")).thenReturn(Optional.of(entity));

    Optional<OpenApiKeyDTO> result = service.findCachedKeyInfo("lx_live_db");
    assertThat(result).isPresent();
    assertThat(result.get().name()).isEqualTo("Key-Db");
  }

  @Test
  @DisplayName("verifyApiKey - 有效 Key 成功返回 LoginToken 及对应角色")
  void testVerifyApiKey_success() throws Exception {
    when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

    OpenApiKeyDTO cached =
        new OpenApiKeyDTO(
            10L, 88L, "Key-Valid", "masked", "kb:read", null, null, null, AiLlmProviderEnum.NONE, null);
    String json = objectMapper.writeValueAsString(cached);
    when(valueOperations.get("openapi:key:lx_live_valid")).thenReturn(json);
    when(adminRoleService.findRolesByUserId(88L)).thenReturn(List.of(AdminRoleEnum.ADMIN));

    Optional<LoginToken> tokenOpt = service.verifyApiKey("lx_live_valid");
    assertThat(tokenOpt).isPresent();
    LoginToken token = tokenOpt.get();
    assertThat(token.userId()).isEqualTo(88L);
    assertThat(token.roles()).containsExactly(AdminRoleEnum.ADMIN);
  }

  @Test
  @DisplayName("verifyApiKey - 空 Key 直接返回 empty")
  void testVerifyApiKey_blank() {
    assertThat(service.verifyApiKey(null)).isEmpty();
    assertThat(service.verifyApiKey("   ")).isEmpty();
  }

  @Test
  @DisplayName("findOpenApiKey - 委托 findCachedKeyInfo 返回结果")
  void testFindOpenApiKey() throws Exception {
    when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

    OpenApiKeyDTO cached =
        new OpenApiKeyDTO(
            10L, 88L, "Key-Valid", "masked", "kb:read", null, null, null, AiLlmProviderEnum.NONE, null);
    String json = objectMapper.writeValueAsString(cached);
    when(valueOperations.get("openapi:key:lx_live_valid")).thenReturn(json);

    Optional<OpenApiKeyDTO> dtoOpt = service.findOpenApiKey("lx_live_valid");
    assertThat(dtoOpt).isPresent();
    assertThat(dtoOpt.get().userId()).isEqualTo(88L);
  }
}
