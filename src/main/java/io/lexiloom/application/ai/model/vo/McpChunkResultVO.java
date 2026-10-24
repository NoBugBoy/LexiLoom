package io.lexiloom.application.ai.model.vo;

/**
 * MCP/Skill 知识库原子切片项视图对象 (不可变 record)
 *
 * <p>提供轻量、无头切片召回数据，包含切片文本、所属文档、分类及视觉坐标元数据。
 */
public record McpChunkResultVO(
    String chunkId,
    Long documentId,
    String documentTitle,
    String categoryName,
    String content,
    Double score,
    Integer chunkIndex,
    Integer pageNumber,
    String deepDocCoordinates) {}
