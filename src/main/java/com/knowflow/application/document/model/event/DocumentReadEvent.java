package com.knowflow.application.document.model.event;

/**
 * 文档查阅领域事件（用于异步累计阅读计数或统计审计）
 *
 * @param documentId 被查阅的文档 ID
 * @param userId 查阅者用户 ID（用于 Redis 用户维度去重，防止同一用户频繁刷新阅读量）
 */
public record DocumentReadEvent(Long documentId, Long userId) {}
