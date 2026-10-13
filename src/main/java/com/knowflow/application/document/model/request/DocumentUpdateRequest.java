package com.knowflow.application.document.model.request;

import com.knowflow.application.enums.WorkSpaceAclEnum;
import jakarta.validation.constraints.Size;

/** 更新文档请求对象 (不可变 record) */
public record DocumentUpdateRequest(
    @Size(max = 200, message = "文档标题最多200字符") String title,
    @Size(max = 500, message = "文档摘要最多500字符") String summary,
    String content,
    Long categoryId,
    WorkSpaceAclEnum visibility,
    String coverUrl) {}
