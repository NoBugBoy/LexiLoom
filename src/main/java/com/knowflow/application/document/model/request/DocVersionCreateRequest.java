package com.knowflow.application.document.model.request;

import jakarta.validation.constraints.Size;

/** 手动创建里程碑版本快照请求 (不可变 record) */
public record DocVersionCreateRequest(
    @Size(max = 50, message = "版本标签最多50字符") String versionTag,
    @Size(max = 500, message = "变更说明最多500字符") String changeSummary) {}
