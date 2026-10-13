package com.knowflow.application.document.model.event;

/**
 * 文档内容更新领域事件 当已发布的文档正文/标题发生更新或执行版本回退时发布，用于触发下游系统（如 Elasticsearch 索引刷新、缓存清理等）
 *
 * @param documentId 文档主键 ID
 * @param workSpaceId 工作区 ID
 * @param operatorId 操作人 ID
 */
public record DocumentUpdatedEvent(Long documentId, Long workSpaceId, Long operatorId) {}
