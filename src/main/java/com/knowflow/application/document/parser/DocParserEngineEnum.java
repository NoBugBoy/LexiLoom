package com.knowflow.application.document.parser;

import lombok.Getter;

/** 文档解析引擎类型枚举 */
@Getter
public enum DocParserEngineEnum {

  /** 自动根据可用引擎自适应匹配（默认优先使用 DeepDoc） */
  AUTO("自适应引擎"),

  /** RAGFlow DeepDoc：专注于 PDF 深度结构化解析、表格与版面识别，提取高质量 Markdown */
  DEEPDOC("DeepDoc 引擎");

  private final String description;

  DocParserEngineEnum(String description) {
    this.description = description;
  }
}
