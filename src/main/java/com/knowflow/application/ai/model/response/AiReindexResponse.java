package com.knowflow.application.ai.model.response;

/** 单文档向量化与切块重构响应值对象 (不可变 record) */
public record AiReindexResponse(Long documentId, long chunkCount, String message) {

  public static AiReindexResponse success(Long documentId, long chunkCount) {
    return new AiReindexResponse(documentId, chunkCount, "切块加冠与向量化索引重建成功");
  }

  public static AiReindexResponse failure(Long documentId, String message) {
    return new AiReindexResponse(documentId, 0L, message);
  }
}
