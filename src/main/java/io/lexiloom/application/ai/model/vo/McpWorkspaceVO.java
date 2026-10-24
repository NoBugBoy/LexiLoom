package io.lexiloom.application.ai.model.vo;

/**
 * MCP/Skill 工作空间发现视图对象 (不可变 record)
 *
 * <p>用于外部 Agent 或 setup.sh 初始化绑定时发现当前凭证可用工作空间列表。
 */
public record McpWorkspaceVO(
    Long id,
    String name,
    String description,
    long totalDocuments,
    long totalChunks) {}
