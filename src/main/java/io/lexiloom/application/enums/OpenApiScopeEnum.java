package io.lexiloom.application.enums;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * OpenAPI 权限范围枚举 (Scopes)
 */
@Getter
@RequiredArgsConstructor
public enum OpenApiScopeEnum {
  /** 知识库元数据与空间列表只读 */
  KB_READ("kb:read", "知识库列表与元数据只读"),

  /** 语义切片检索与 Cross-Encoder 重排召回 */
  KB_SEARCH("kb:search", "语义切片检索与重排召回"),

  /** 单文档正文及详情查看 */
  DOC_READ("doc:read", "单文档正文与详情查看"),

  /** 大模型补全与 RAG 问答会话 */
  CHAT_COMPLETION("chat:completion", "大模型问答会话与生成");

  @JsonValue
  private final String code;
  private final String description;

  /**
   * 将逗号分隔的 scopes 字符串解析为集合
   *
   * @param scopesStr 逗号分隔的 scope 字符串
   * @return Scope 代码集合
   */
  public static Set<String> parseScopes(String scopesStr) {
    if (StrUtil.isBlank(scopesStr)) {
      return Collections.emptySet();
    }
    return Arrays.stream(scopesStr.split(","))
        .map(String::trim)
        .filter(StrUtil::isNotBlank)
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * 判断逗号分隔的 scopes 字符串中是否包含指定 scope
   *
   * @param scopesStr 逗号分隔的 scope 字符串
   * @param scope 目标权限
   * @return true 若包含
   */
  public static boolean contains(String scopesStr, OpenApiScopeEnum scope) {
    if (scope == null || StrUtil.isBlank(scopesStr)) {
      return false;
    }
    return parseScopes(scopesStr).contains(scope.getCode());
  }

  @JsonCreator
  public static @Nullable OpenApiScopeEnum fromValue(@Nullable String value) {
    if (StrUtil.isBlank(value)) {
      return null;
    }
    String clean = value.trim().toLowerCase();
    return switch (clean) {
      case "kb:read", "kbread" -> KB_READ;
      case "kb:search", "kbsearch" -> KB_SEARCH;
      case "doc:read", "docread" -> DOC_READ;
      case "chat:completion", "chatcompletion" -> CHAT_COMPLETION;
      default -> {
        try {
          yield valueOf(value.trim().toUpperCase());
        } catch (Exception ex) {
          yield null;
        }
      }
    };
  }

  public static Optional<OpenApiScopeEnum> ofNullable(@Nullable String value) {
    return Optional.ofNullable(fromValue(value));
  }
}
