package io.lexiloom.application.ai.rag;

import io.lexiloom.application.ai.model.request.AiChatRequest;
import io.lexiloom.application.ai.model.response.AiChatResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RAG 智能问答与检索增强编排核心服务契约 (RagChatService)
 *
 * <p>提供同步阻塞式问答与基于 SSE 的流式问答，深度编排：
 *
 * <ol>
 *   <li>多租户双路召回 (BM25 + Dense Vector) 与精排；
 *   <li>视觉坐标溯源引用源生成 (CitationVO)；
 *   <li>防幻觉人设注入与 Prompt 上下文组装；
 *   <li>大模型流式输出与思维链推理分流推送；
 *   <li>基于 Redis 的多轮会话状态管理与自动续期。
 * </ol>
 */
public interface RagChatService {

  /**
   * 同步阻塞式 RAG 问答
   *
   * @param request AI 对话请求参数
   * @return 完整回复响应（含溯源引用、思考过程、耗时与会话 ID）
   */
  AiChatResponse chat(AiChatRequest request);

  /**
   * 异步流式 SSE RAG 问答
   *
   * @param request AI 对话请求参数
   * @param emitter SSE 发射器通道
   */
  void streamChat(AiChatRequest request, SseEmitter emitter);
}
