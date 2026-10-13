package com.knowflow.application.ai.model.vo;

import java.util.List;

/**
 * 结构化文档切片详情值对象 (不可变 record)
 *
 * <p>用于向前端或调试端点透传 DeepDoc 原子切块详情、版面类型、自然页码及像素级物理坐标。
 */
public record DocChunkVO(
    String id,
    Long documentId,
    Integer chunkIndex,
    String headerPath,
    String content,
    String contextPrefix,
    Double similarityScore,
    Integer page,
    List<Float> bbox,
    String chunkType,
    String anchor) {

  public DocChunkVO {
    bbox = (bbox == null) ? List.of() : List.copyOf(bbox);
  }

  public DocChunkVO(
      String id,
      Long documentId,
      Integer chunkIndex,
      String headerPath,
      String content,
      Double similarityScore,
      Integer page,
      List<Float> bbox,
      String chunkType,
      String anchor) {
    this(
        id,
        documentId,
        chunkIndex,
        headerPath,
        content,
        null,
        similarityScore,
        page,
        bbox,
        chunkType,
        anchor);
  }

  public static DocChunkVO fromScored(
      com.knowflow.application.ai.gateway.rerank.ScoredDocChunkVO scored) {
    if (scored == null || scored.chunk() == null) {
      return null;
    }
    return fromDocument(scored.chunk(), scored.score());
  }

  public static DocChunkVO fromDocument(
      com.knowflow.application.ai.model.search.DocChunkDocument doc, Double score) {
    if (doc == null) {
      return null;
    }
    Long docId = null;
    try {
      if (doc.getDocumentId() != null) {
        docId = Long.parseLong(doc.getDocumentId());
      }
    } catch (NumberFormatException ignored) {
    }
    return new DocChunkVO(
        doc.getId(),
        docId,
        doc.getChunkIndex(),
        doc.getHeaderPath(),
        doc.getContent(),
        doc.getContextPrefix(),
        score,
        doc.getPage(),
        doc.getBbox(),
        doc.getChunkType(),
        doc.getAnchor());
  }
}
