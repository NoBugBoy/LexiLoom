package io.lexiloom.application.ai.model.response;

import io.lexiloom.application.ai.model.vo.CitationVO;
import java.util.List;

/**
 * SSE 流式对话传输块对象 (不可变 record)
 *
 * <p>type 取值:
 *
 * <ul>
 *   <li>{@code THINKING}: 深度推理/思维链增量内容 (DeepSeek-R1)
 *   <li>{@code MESSAGE}: 正文回答增量内容
 *   <li>{@code CITATION}: 结构化引用源数组 (包含视觉坐标 bbox)
 *   <li>{@code DONE}: 生成结束标记
 * </ul>
 */
public record AiChatChunkResponse(
    String type, String delta, List<CitationVO> citations, String sessionId, String traceId) {

  public static final String TYPE_THINKING = "THINKING";
  public static final String TYPE_MESSAGE = "MESSAGE";
  public static final String TYPE_CITATION = "CITATION";
  public static final String TYPE_DONE = "DONE";

  public AiChatChunkResponse {
    citations = (citations == null) ? List.of() : List.copyOf(citations);
  }

  public AiChatChunkResponse(
      String type, String delta, List<CitationVO> citations, String sessionId) {
    this(type, delta, citations, sessionId, null);
  }

  public AiChatChunkResponse(String type, String delta, List<CitationVO> citations) {
    this(type, delta, citations, null, null);
  }

  public static AiChatChunkResponse message(String delta) {
    return new AiChatChunkResponse(TYPE_MESSAGE, delta, List.of(), null, null);
  }

  public static AiChatChunkResponse thinking(String delta) {
    return new AiChatChunkResponse(TYPE_THINKING, delta, List.of(), null, null);
  }

  public static AiChatChunkResponse citation(List<CitationVO> citations) {
    return new AiChatChunkResponse(TYPE_CITATION, null, citations, null, null);
  }

  public static AiChatChunkResponse citation(List<CitationVO> citations, String sessionId) {
    return new AiChatChunkResponse(TYPE_CITATION, null, citations, sessionId, null);
  }

  public static AiChatChunkResponse citation(
      List<CitationVO> citations, String sessionId, String traceId) {
    return new AiChatChunkResponse(TYPE_CITATION, null, citations, sessionId, traceId);
  }

  public static AiChatChunkResponse done() {
    return new AiChatChunkResponse(TYPE_DONE, null, List.of(), null, null);
  }

  public static AiChatChunkResponse done(String sessionId) {
    return new AiChatChunkResponse(TYPE_DONE, null, List.of(), sessionId, null);
  }

  public static AiChatChunkResponse done(String sessionId, String traceId) {
    return new AiChatChunkResponse(TYPE_DONE, null, List.of(), sessionId, traceId);
  }
}
