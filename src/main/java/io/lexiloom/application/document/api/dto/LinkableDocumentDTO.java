package io.lexiloom.application.document.api.dto;

import io.lexiloom.application.enums.WorkSpaceAclEnum;
import java.time.LocalDateTime;

/** 可申请跨空间关联的文档元数据传输对象 (仅包含名称与概要元数据，严格不暴露正文内容) */
public record LinkableDocumentDTO(
    Long documentId,
    String title,
    Long categoryId,
    WorkSpaceAclEnum visibility,
    LocalDateTime updateTime) {}
