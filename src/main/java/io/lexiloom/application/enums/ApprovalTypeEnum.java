package io.lexiloom.application.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 通用审批业务类型枚举 */
@Getter
@RequiredArgsConstructor
public enum ApprovalTypeEnum {
  /** 文档导入解析完成后的发布审批 */
  DOCUMENT_AUDIT("文档发布审批"),

  /** 工作空间跨域关联与文档精准授权审批 */
  SPACE_LINK("空间关联与文档授权审批");

  private final String description;
}
