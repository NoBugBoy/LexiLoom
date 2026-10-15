package io.lexiloom.application.document.model.event;

/**
 * 文档删除领域事件
 *
 * @param documentId 文档主键 ID
 * @param workSpaceId 工作区 ID
 * @param operatorId 操作人用户 ID
 */
public record DocumentDeletedEvent(Long documentId, Long workSpaceId, Long operatorId) {}
