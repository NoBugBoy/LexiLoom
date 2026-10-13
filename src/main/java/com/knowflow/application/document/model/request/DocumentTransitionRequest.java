package com.knowflow.application.document.model.request;

import com.knowflow.application.document.statemachine.DocumentEventEnum;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 文档状态机流转请求 (不可变 record) */
public record DocumentTransitionRequest(
    @NotNull(message = "流转事件不能为空") DocumentEventEnum event,
    @Size(max = 500, message = "流转意见/原因最多500字符") String reason) {}
