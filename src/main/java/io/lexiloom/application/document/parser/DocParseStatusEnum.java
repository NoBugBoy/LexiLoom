package io.lexiloom.application.document.parser;

import lombok.Getter;

/** 文档解析状态枚举 */
@Getter
public enum DocParseStatusEnum {

  /** 队列排队等待中 */
  QUEUED("排队中"),

  /** 解析成功，产出完整 Markdown 内容 */
  SUCCESS("解析成功"),

  /** 异步解析中（已提交至 DeepDoc） */
  ASYNC_PROCESSING("异步解析中"),

  /** 解析失败 */
  FAILED("解析失败");

  private final String description;

  DocParseStatusEnum(String description) {
    this.description = description;
  }
}
