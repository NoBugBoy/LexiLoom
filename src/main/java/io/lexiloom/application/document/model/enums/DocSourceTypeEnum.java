package io.lexiloom.application.document.model.enums;

import lombok.Getter;

/** 文档内容来源渠道 */
@Getter
public enum DocSourceTypeEnum {

  /** 外部文件导入：Word, PDF, Markdown, TXT 等文件摄入 */
  IMPORT("文件导入"),

  /** AI 辅助生成：大模型大纲生成或提炼扩写 */
  AI_GENERATED("AI生成");

  private final String description;

  DocSourceTypeEnum(String description) {
    this.description = description;
  }
}
