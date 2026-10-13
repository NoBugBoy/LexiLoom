package com.knowflow.application.document.search.dto;

import java.time.LocalDateTime;

/** 搜索历史记录领域事件 (用于异步落库归档) */
public record SearchHistoryRecordEvent(
    Long userId, Long workspaceId, String keyword, LocalDateTime searchAt) {}
