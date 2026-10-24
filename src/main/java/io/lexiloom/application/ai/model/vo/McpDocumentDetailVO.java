package io.lexiloom.application.ai.model.vo;

import cn.hutool.core.date.DatePattern;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;

/**
 * MCP/Skill 单文档正文详情视图对象 (不可变 record)
 *
 * <p>供 Agent 通读特定核心文档未切分全文或大纲时使用。
 */
public record McpDocumentDetailVO(
    Long id,
    String title,
    Long workspaceId,
    String categoryName,
    String status,
    Integer version,
    String content,
    @JsonFormat(pattern = DatePattern.NORM_DATETIME_PATTERN) LocalDateTime createTime,
    @JsonFormat(pattern = DatePattern.NORM_DATETIME_PATTERN) LocalDateTime updateTime) {}
