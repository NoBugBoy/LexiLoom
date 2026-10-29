package io.lexiloom.application.document.model.enums;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * 文档内容来源渠道枚举 (Doc Source Type Enum)
 * <p>
 * 遵循充血模型与值对象设计，自闭合管理文档来源语义、物理文件依赖判断以及容错反序列化
 */
@Getter
@RequiredArgsConstructor
public enum DocSourceTypeEnum {

  /** 外部文件导入：Word, PDF, Markdown, TXT 等文件摄入（强依赖来源原始文件记录） */
  IMPORT("IMPORT", "文件导入"),

  /** AI 辅助生成：大模型大纲生成、知识库提炼或智能扩写（无底层物理文件） */
  AI_GENERATED("AI_GENERATED", "AI生成"),

  /** 在线手动撰写：在知识库中空白新建或在线编辑 Markdown */
  MANUAL("MANUAL", "手动创建");

  @JsonValue
  private final String code;
  private final String description;

  /**
   * 判断当前来源是否由物理文件资产支撑 (如本地上传或对象存储导入)
   *
   * @return 若必须有 DocSourceFileEntity 支撑则返回 true
   */
  public boolean isFileBased() {
    return this == IMPORT;
  }

  /**
   * 判断是否由 AI 渠道生成
   *
   * @return 若为 AI 模型生成则返回 true
   */
  public boolean isAiGenerated() {
    return this == AI_GENERATED;
  }

  /**
   * 判断是否为手动在线编写
   *
   * @return 若为在线手动创建则返回 true
   */
  public boolean isManual() {
    return this == MANUAL;
  }

  /**
   * Jackson 容错反序列化与参数解析工厂 (支持 code / name / description 不区分大小写与空白清洗)
   *
   * @param value 来源字符串
   * @return 匹配的枚举实例，未识别时返回 null
   */
  @JsonCreator
  public static @Nullable DocSourceTypeEnum fromValue(@Nullable String value) {
    if (StrUtil.isBlank(value)) {
      return null;
    }
    String clean = value.trim().toUpperCase();
    return switch (clean) {
      case "IMPORT", "文件导入" -> IMPORT;
      case "AI_GENERATED", "AI生成", "AIGC" -> AI_GENERATED;
      case "MANUAL", "手动创建", "手工新建" -> MANUAL;
      default -> null;
    };
  }

  /**
   * 空安全解析工厂 (返回 Optional，严禁抛出 IllegalArgumentException)
   *
   * @param value 来源字符串
   * @return 包含匹配枚举的 Optional，未匹配或为空则返回 Optional.empty()
   */
  public static Optional<DocSourceTypeEnum> ofNullable(@Nullable String value) {
    return Optional.ofNullable(fromValue(value));
  }

  /**
   * 安全解析并提供默认回退兜底
   *
   * @param value 来源字符串
   * @param defaultType 兜底枚举
   * @return 匹配枚举或默认兜底
   */
  public static DocSourceTypeEnum ofOrDefault(@Nullable String value, DocSourceTypeEnum defaultType) {
    return ofNullable(value).orElse(defaultType);
  }
}
