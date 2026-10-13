package com.knowflow.application.ai.model.vo;

import java.util.List;

/**
 * 视觉高保真引用溯源值对象 (不可变 record)
 *
 * <p>用于在线 RAG 问答中下发给前端进行段落溯源与 PDF 原件像素级高亮画框（Visual Grounding）。
 */
public record CitationVO(
    Integer citationIndex,
    Long documentId,
    String docTitle,
    String headerPath,
    String snippet,
    Double score,
    Integer page,
    List<Float> bbox,
    String chunkType,
    String anchor) {

  public CitationVO {
    bbox = (bbox == null) ? List.of() : List.copyOf(bbox);
  }
}
