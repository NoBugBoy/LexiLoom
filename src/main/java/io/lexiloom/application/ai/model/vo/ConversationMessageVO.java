package io.lexiloom.application.ai.model.vo;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * AI 对话历史消息视图对象 (不可变 record)
 *
 * <p>完整封装单条对话消息，包括正文、发送时间、视觉溯源引用证据链以及深度推理思维链， 支持前端在回看历史会话时无损复原溯源卡片与思维过程。
 *
 * @param role 消息角色 (user / assistant)
 * @param content 消息正文内容
 * @param createTime 消息发送时间 (yyyy-MM-dd HH:mm:ss)
 * @param citations 视觉几何坐标引用溯源卡片列表 (助手回答时携带，用户提问为空列表)
 * @param thinking 深度推理思维链内容 (助手回答且启用推理模型时携带)
 */
public record ConversationMessageVO(
    String role,
    String content,
    String createTime,
    List<CitationVO> citations,
    String thinking,
    List<ToolEventVO> tools) {

  private static final DateTimeFormatter FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  public ConversationMessageVO {
    if (createTime == null || createTime.isBlank()) {
      createTime = LocalDateTime.now().format(FORMATTER);
    }
    citations = (citations == null) ? List.of() : List.copyOf(citations);
    tools = (tools == null) ? List.of() : List.copyOf(tools);
  }

  /** Backward-compatible constructor for existing persisted message JSON and callers. */
  public ConversationMessageVO(
      String role, String content, String createTime, List<CitationVO> citations, String thinking) {
    this(role, content, createTime, citations, thinking, List.of());
  }

  /** 向后兼容的 3 参数构造器（用于历史旧数据反序列化与普通无引用消息） */
  public ConversationMessageVO(String role, String content, String createTime) {
    this(role, content, createTime, List.of(), null, List.of());
  }

  public static ConversationMessageVO of(String role, String content) {
    return new ConversationMessageVO(
        role, content, LocalDateTime.now().format(FORMATTER), List.of(), null);
  }

  public static ConversationMessageVO ofUser(String content) {
    return of("user", content);
  }

  public static ConversationMessageVO ofAssistant(
      String content, List<CitationVO> citations, String thinking) {
    return new ConversationMessageVO(
        "assistant", content, LocalDateTime.now().format(FORMATTER), citations, thinking);
  }
}
