package io.lexiloom.application.common.security;

import cn.hutool.core.util.StrUtil;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * OpenAPI 凭据在全局安全上下文中的轻量凭据模型
 */
public record OpenApiPrincipal(
    Long keyId,
    Long userId,
    String name,
    String scopes,
    @Nullable String allowedWorkspaceIds,
    String llmProvider,
    @Nullable String llmApiKey)
    implements Serializable {

  /**
   * 检查是否包含指定的 Scope 权限
   *
   * @param scope Scope 代码 (如 kb:search, kb:read)
   * @return true 若包含
   */
  public boolean hasScope(String scope) {
    if (StrUtil.isBlank(scope) || StrUtil.isBlank(this.scopes)) {
      return false;
    }
    return parseScopeSet().contains(scope.trim());
  }

  /**
   * 检查目标工作空间是否在当前 Key 的允许白名单内
   *
   * @param workspaceId 目标工作空间 ID
   * @return 若未限定空间白名单则返回 true (遵循用户本身空间权限)；若有限定则必须在白名单内
   */
  public boolean isWorkspaceAllowed(Long workspaceId) {
    if (workspaceId == null) {
      return false;
    }
    Set<Long> allowed = parseAllowedWorkspaceIds();
    if (allowed.isEmpty()) {
      return true;
    }
    return allowed.contains(workspaceId);
  }

  public Set<String> parseScopeSet() {
    if (StrUtil.isBlank(this.scopes)) {
      return Collections.emptySet();
    }
    return Arrays.stream(this.scopes.split(","))
        .map(String::trim)
        .filter(StrUtil::isNotBlank)
        .collect(Collectors.toUnmodifiableSet());
  }

  public Set<Long> parseAllowedWorkspaceIds() {
    if (StrUtil.isBlank(this.allowedWorkspaceIds)) {
      return Collections.emptySet();
    }
    return Arrays.stream(this.allowedWorkspaceIds.split(","))
        .map(String::trim)
        .filter(StrUtil::isNotBlank)
        .map(
            s -> {
              try {
                return Long.parseLong(s);
              } catch (NumberFormatException e) {
                return null;
              }
            })
        .filter(Objects::nonNull)
        .collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public String toString() {
    return "OpenApiPrincipal[keyId="
        + keyId
        + ", userId="
        + userId
        + ", name="
        + name
        + ", scopes="
        + scopes
        + ", allowedWorkspaceIds="
        + allowedWorkspaceIds
        + ", llmProvider="
        + llmProvider
        + ", llmApiKey="
        + (llmApiKey == null ? "null" : "[PROTECTED]")
        + "]";
  }
}
