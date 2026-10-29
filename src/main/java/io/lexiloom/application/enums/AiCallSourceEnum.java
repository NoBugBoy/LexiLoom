package io.lexiloom.application.enums;

import cn.hutool.core.util.StrUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** AI 全链路调用来源渠道枚举 (严禁自定义，仅限以下三种受管类型) */
@Getter
@RequiredArgsConstructor
public enum AiCallSourceEnum {

  /** 终端命令行技能 / 智能体调用 (CLI / Skill / Agent) */
  CLI_SKILL("CLI_SKILL", "终端与技能调用"),

  /** 开放平台标准外部系统调用 (OpenAPI) */
  OPENAPI("OPENAPI", "开放接口调用"),

  /** 网页端用户在线对话 (Web) */
  WEB_CHAT("WEB_CHAT", "网页端对话");

  private final String code;
  private final String description;

  /**
   * 从字符串解析或回退 (将历史存量 AGENT_MCP 兼容归纳为 CLI_SKILL，未知自定义输入严格归一化回退)
   *
   * @param source 传入来源字符串
   * @param defaultSource 解析失败或为空时的默认来源
   * @return 匹配的受管枚举实例
   */
  public static AiCallSourceEnum from(String source, AiCallSourceEnum defaultSource) {
    if (StrUtil.isBlank(source)) {
      return defaultSource != null ? defaultSource : WEB_CHAT;
    }
    String clean = source.trim().toUpperCase();
    return switch (clean) {
      case "CLI_SKILL" -> CLI_SKILL;
      case "OPENAPI" -> OPENAPI;
      case "WEB_CHAT" -> WEB_CHAT;
      default -> defaultSource != null ? defaultSource : WEB_CHAT;
    };
  }
}
