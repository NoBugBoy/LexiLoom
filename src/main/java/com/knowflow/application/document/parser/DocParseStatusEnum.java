package com.knowflow.application.document.parser;

import lombok.Getter;

/** 文档解析状态枚举 */
@Getter
public enum DocParseStatusEnum {

  /** 解析成功，产出完整 Markdown 内容 */
  SUCCESS("解析成功"),

  /** 仅作为固化版面附件预览（如 PDF 双轨制默认轨） */
  PREVIEW_ONLY("仅附件预览"),

  /** 异步排队处理中 */
  ASYNC_PROCESSING("异步解析中"),

  /** 解析失败 */
  FAILED("解析失败");

  private final String description;

  DocParseStatusEnum(String description) {
    this.description = description;
  }
}
