package io.lexiloom.application.enums;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/** 通用审批业务类型枚举 */
@Getter
@RequiredArgsConstructor
public enum ApprovalTypeEnum {
  /** 文档导入解析完成后的发布审批 */
  DOCUMENT_AUDIT("DOCUMENT_AUDIT", "文档发布审批"),

  /** 工作空间跨域关联与文档精准授权审批 */
  SPACE_LINK("SPACE_LINK", "空间关联与文档授权审批");

  @JsonValue
  private final String code;
  private final String description;

  /** 是否为文档发布审批 */
  public boolean isDocumentAudit() {
    return this == DOCUMENT_AUDIT;
  }

  /** 是否为工作空间横向关联审批 */
  public boolean isSpaceLink() {
    return this == SPACE_LINK;
  }

  @JsonCreator
  public static @Nullable ApprovalTypeEnum fromValue(@Nullable String value) {
    if (StrUtil.isBlank(value)) {
      return null;
    }
    String clean = value.trim().toUpperCase();
    return switch (clean) {
      case "DOCUMENT_AUDIT", "文档发布审批", "文档审批" -> DOCUMENT_AUDIT;
      case "SPACE_LINK", "空间关联与文档授权审批", "空间关联" -> SPACE_LINK;
      default -> null;
    };
  }

  public static Optional<ApprovalTypeEnum> ofNullable(@Nullable String value) {
    return Optional.ofNullable(fromValue(value));
  }

  public static ApprovalTypeEnum ofOrDefault(@Nullable String value, ApprovalTypeEnum fallback) {
    return ofNullable(value).orElse(fallback);
  }
}
