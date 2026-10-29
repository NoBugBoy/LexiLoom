package io.lexiloom.application.document.api.dto;

import cn.hutool.core.date.DatePattern;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;

/** 分类目录数据传输对象 (不可变 record) */
public record DocCategoryDTO(
    Long id,
    Long workSpaceId,
    Long parentId,
    String name,
    Integer sortOrder,
    Integer level,
    String path,
    @JsonFormat(pattern = DatePattern.NORM_DATETIME_PATTERN) LocalDateTime createTime) {}
