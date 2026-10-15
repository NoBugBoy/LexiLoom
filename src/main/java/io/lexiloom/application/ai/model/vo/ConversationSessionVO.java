package io.lexiloom.application.ai.model.vo;

/**
 * AI 对话会话元数据视图对象 (不可变 record)
 *
 * @param sessionId 会话唯一标识 (UUID)
 * @param title 会话标题 (首轮对话内容截取或用户自定义)
 * @param workspaceId 所属工作空间 ID
 * @param userId 所属用户 ID
 * @param messageCount 会话历史消息总条数
 * @param createTime 会话创建时间 (yyyy-MM-dd HH:mm:ss)
 * @param updateTime 会话最后活跃/更新时间 (yyyy-MM-dd HH:mm:ss)
 */
public record ConversationSessionVO(
    String sessionId,
    String title,
    Long workspaceId,
    Long userId,
    int messageCount,
    String createTime,
    String updateTime) {}
