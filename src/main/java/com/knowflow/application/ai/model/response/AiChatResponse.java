package com.knowflow.application.ai.model.response;

import com.knowflow.application.ai.model.vo.CitationVO;
import java.util.List;

/** AI 同步阻塞对话响应值对象 (不可变 record) */
public record AiChatResponse(
    String content,
    String thinkingContent,
    List<CitationVO> citations,
    long latencyMs,
    String sessionId) {

  public AiChatResponse {
    citations = (citations == null) ? List.of() : List.copyOf(citations);
  }

  public AiChatResponse(
      String content, String thinkingContent, List<CitationVO> citations, long latencyMs) {
    this(content, thinkingContent, citations, latencyMs, null);
  }

  public static AiChatResponse of(String content, List<CitationVO> citations, long latencyMs) {
    return new AiChatResponse(content, null, citations, latencyMs, null);
  }

  public static AiChatResponse of(
      String content, String thinkingContent, List<CitationVO> citations, long latencyMs) {
    return new AiChatResponse(content, thinkingContent, citations, latencyMs, null);
  }

  public static AiChatResponse of(
      String content,
      String thinkingContent,
      List<CitationVO> citations,
      long latencyMs,
      String sessionId) {
    return new AiChatResponse(content, thinkingContent, citations, latencyMs, sessionId);
  }
}
