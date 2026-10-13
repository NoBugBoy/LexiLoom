package com.knowflow.application.document.statemachine;

import com.knowflow.application.document.model.entity.DocumentEntity;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/** 文档状态机执行上下文 (不可变 record) 携带主文档聚合根、操作人、审批意见及扩展参数，供 Condition 校验与 Action 业务副作用执行 */
public record DocumentStateContext(
    Long documentId,
    DocumentEntity document,
    Long operatorId,
    String operatorRole,
    String comment,
    Map<String, Object> extraData) {

  public DocumentStateContext {
    Objects.requireNonNull(documentId, "文档ID不能为空");
    Objects.requireNonNull(operatorId, "操作人ID不能为空");
    extraData =
        (extraData == null) ? Collections.emptyMap() : Collections.unmodifiableMap(extraData);
  }

  public static DocumentStateContext of(Long documentId, Long operatorId) {
    return new DocumentStateContext(
        documentId, null, operatorId, null, null, Collections.emptyMap());
  }

  public static DocumentStateContext of(Long documentId, Long operatorId, String comment) {
    return new DocumentStateContext(
        documentId, null, operatorId, null, comment, Collections.emptyMap());
  }

  public static DocumentStateContext of(
      Long documentId,
      DocumentEntity document,
      Long operatorId,
      String operatorRole,
      String comment) {
    return new DocumentStateContext(
        documentId, document, operatorId, operatorRole, comment, Collections.emptyMap());
  }

  public static DocumentStateContext of(
      Long documentId,
      DocumentEntity document,
      Long operatorId,
      String operatorRole,
      String comment,
      Map<String, Object> extraData) {
    return new DocumentStateContext(
        documentId, document, operatorId, operatorRole, comment, extraData);
  }
}
