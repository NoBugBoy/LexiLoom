package io.lexiloom.application.user.model.entity;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.common.BaseEntity;
import io.lexiloom.application.enums.AiLlmProviderEnum;
import io.lexiloom.application.enums.OpenApiKeyStatusEnum;
import io.lexiloom.application.enums.OpenApiScopeEnum;
import io.lexiloom.application.exception.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * OpenAPI 开放接口访问凭据实体 (ll_openapi_key)
 *
 * <p>支持单账号创建多个独立 Key，支持细粒度 Scopes 权限、工作空间绑定白名单及 BYOK 自带大模型 Key。
 */
@Entity
@Table(
    name = "ll_openapi_key",
    comment = "OpenAPI开放凭据表",
    indexes = {
      @Index(name = "idx_openapi_key_uid", columnList = "user_id, del_flag"),
      @Index(name = "idx_openapi_key_key", columnList = "api_key, del_flag", unique = true)
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OpenApiKeyEntity extends BaseEntity {

  /** 归属的用户/管理员主键 ID */
  @Column(name = "user_id", comment = "用户主键ID", nullable = false)
  private Long userId;

  /** 密钥备注名称 (如 OA-System-Prod, Cursor-Skill) */
  @Column(name = "name", comment = "密钥备注名称", nullable = false, length = 64)
  private String name;

  /** 真实生成的完整 API 密钥串 (如 lx_live_xxx) */
  @Column(name = "api_key", comment = "真实API密钥串", nullable = false, length = 128)
  private String apiKey;

  /** 脱敏展示密钥串 (如 lx_live_8f3a****9d2e) */
  @Column(name = "masked_key", comment = "脱敏API密钥串", nullable = false, length = 64)
  private String maskedKey;

  /** 细粒度权限列表，逗号分隔 (如 kb:read,kb:search,doc:read) */
  @Column(name = "scopes", comment = "权限范围列表", length = 256)
  private String scopes;

  /** 允许访问的工作空间 ID 列表，逗号分隔 (留空表示不限制，遵循用户全部工作空间权限) */
  @Column(name = "allowed_workspace_ids", comment = "限定工作空间ID列表", length = 512)
  private String allowedWorkspaceIds;

  /** 密钥启用状态 */
  @Enumerated(EnumType.STRING)
  @Column(name = "status", comment = "启用状态", nullable = false, length = 32)
  private OpenApiKeyStatusEnum status = OpenApiKeyStatusEnum.ENABLED;

  /** 过期时间 (null 表示永不过期) */
  @Column(name = "expires_at", comment = "过期时间")
  private LocalDateTime expiresAt;

  /** 最近一次成功调用鉴权的时间 */
  @Column(name = "last_used_time", comment = "最近调用时间")
  private LocalDateTime lastUsedTime;

  /** 累计调用总次数 */
  @Column(name = "total_calls", comment = "累计调用总次数", nullable = false)
  private Long totalCalls = 0L;

  /** BYOK 用户自有 LLM 厂商 (NONE / QWEN / DEEPSEEK) */
  @Enumerated(EnumType.STRING)
  @Column(name = "llm_provider", comment = "自带LLM厂商", nullable = false, length = 32)
  private AiLlmProviderEnum llmProvider = AiLlmProviderEnum.NONE;

  /** BYOK 用户自有 LLM API 密钥 */
  @Column(name = "llm_api_key", comment = "自带LLM密钥", length = 256)
  private String llmApiKey;

  /**
   * 构造并创建一个新的 OpenAPI 凭据聚合根
   */
  public static OpenApiKeyEntity create(
      Long userId,
      String name,
      String apiKey,
      String maskedKey,
      @Nullable String scopes,
      @Nullable String allowedWorkspaceIds,
      @Nullable LocalDateTime expiresAt,
      @Nullable AiLlmProviderEnum llmProvider,
      @Nullable String llmApiKey) {
    if (userId == null) {
      throw BusinessException.badRequest("用户标识不能为空");
    }
    if (StrUtil.isBlank(name)) {
      throw BusinessException.badRequest("密钥名称不能为空");
    }
    if (StrUtil.isBlank(apiKey) || StrUtil.isBlank(maskedKey)) {
      throw BusinessException.badRequest("生成的密钥数据不完整");
    }

    OpenApiKeyEntity entity = new OpenApiKeyEntity();
    entity.userId = userId;
    entity.name = name.trim();
    entity.apiKey = apiKey.trim();
    entity.maskedKey = maskedKey.trim();
    entity.scopes = StrUtil.isNotBlank(scopes) ? scopes.trim() : "kb:read,kb:search,doc:read";
    entity.allowedWorkspaceIds = normalizeWorkspaceIds(allowedWorkspaceIds);
    entity.status = OpenApiKeyStatusEnum.ENABLED;
    entity.expiresAt = expiresAt;
    entity.totalCalls = 0L;
    entity.llmProvider = llmProvider != null ? llmProvider : AiLlmProviderEnum.NONE;
    entity.llmApiKey = StrUtil.isNotBlank(llmApiKey) ? llmApiKey.trim() : null;
    return entity;
  }

  /**
   * 重新生成轮转密钥
   */
  public void rotateKey(String newApiKey, String newMaskedKey) {
    if (StrUtil.isBlank(newApiKey) || StrUtil.isBlank(newMaskedKey)) {
      throw BusinessException.badRequest("新轮转密钥不能为空");
    }
    this.apiKey = newApiKey.trim();
    this.maskedKey = newMaskedKey.trim();
  }

  /**
   * 修改配置属性
   */
  public void updateConfig(
      String name,
      @Nullable String scopes,
      @Nullable String allowedWorkspaceIds,
      @Nullable LocalDateTime expiresAt,
      @Nullable AiLlmProviderEnum llmProvider,
      @Nullable String llmApiKey) {
    if (StrUtil.isNotBlank(name)) {
      this.name = name.trim();
    }
    if (scopes != null) {
      this.scopes = scopes.trim();
    }
    if (allowedWorkspaceIds != null) {
      this.allowedWorkspaceIds = normalizeWorkspaceIds(allowedWorkspaceIds);
    }
    this.expiresAt = expiresAt;
    if (llmProvider != null) {
      this.llmProvider = llmProvider;
    }
    if (llmApiKey != null) {
      this.llmApiKey = StrUtil.isNotBlank(llmApiKey) ? llmApiKey.trim() : null;
    }
  }

  public void enable() {
    this.status = OpenApiKeyStatusEnum.ENABLED;
  }

  public void disable() {
    this.status = OpenApiKeyStatusEnum.DISABLED;
  }

  /**
   * 记录一次调用 (更新最近时间和调用次数)
   */
  public void recordUsage() {
    this.lastUsedTime = LocalDateTime.now();
    this.totalCalls = (this.totalCalls != null ? this.totalCalls : 0L) + 1L;
  }

  /**
   * 判断当前 Key 是否有效且可调用 (未禁用、未过期)
   */
  public boolean isUsable() {
    if (this.status != OpenApiKeyStatusEnum.ENABLED) {
      return false;
    }
    if (this.expiresAt != null && LocalDateTime.now().isAfter(this.expiresAt)) {
      return false;
    }
    return true;
  }

  /**
   * 校验当前 Key 是否拥有特定 Scope 权限
   */
  public boolean hasScope(OpenApiScopeEnum scope) {
    if (scope == null) {
      return false;
    }
    return hasScope(scope.getCode());
  }

  public boolean hasScope(String scopeCode) {
    if (StrUtil.isBlank(scopeCode)) {
      return false;
    }
    return parseScopeSet().contains(scopeCode.trim());
  }

  /**
   * 校验目标工作空间是否允许通过此 Key 访问
   *
   * @param workspaceId 目标工作空间 ID
   * @return 若 allowedWorkspaceIds 为空则默认允许 (后续由用户本身空间角色决定)，若非空则必须在白名单内
   */
  public boolean isWorkspaceAllowed(Long workspaceId) {
    if (workspaceId == null) {
      return false;
    }
    Set<Long> allowedIds = parseAllowedWorkspaceIdSet();
    if (allowedIds.isEmpty()) {
      return true;
    }
    return allowedIds.contains(workspaceId);
  }

  /**
   * 获取 Scope 集合
   */
  public Set<String> parseScopeSet() {
    return OpenApiScopeEnum.parseScopes(this.scopes);
  }

  /**
   * 获取允许访问的工作空间 ID 集合
   */
  public Set<Long> parseAllowedWorkspaceIdSet() {
    if (StrUtil.isBlank(this.allowedWorkspaceIds)) {
      return Collections.emptySet();
    }
    return Arrays.stream(this.allowedWorkspaceIds.split(","))
        .map(String::trim)
        .filter(StrUtil::isNotBlank)
        .map(s -> {
          try {
            return Long.parseLong(s);
          } catch (NumberFormatException e) {
            return null;
          }
        })
        .filter(Objects::nonNull)
        .collect(Collectors.toUnmodifiableSet());
  }

  private static String normalizeWorkspaceIds(@Nullable String workspaceIds) {
    if (StrUtil.isBlank(workspaceIds)) {
      return "";
    }
    return Arrays.stream(workspaceIds.split(","))
        .map(String::trim)
        .filter(StrUtil::isNotBlank)
        .collect(Collectors.joining(","));
  }
}
