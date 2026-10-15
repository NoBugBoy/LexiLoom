package io.lexiloom.application.ai.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand;
import io.lexiloom.application.ai.gateway.llm.LlmGateway;
import io.lexiloom.application.ai.gateway.llm.LlmStreamCallback;
import io.lexiloom.application.ai.gateway.rerank.ScoredDocChunkVO;
import io.lexiloom.application.ai.model.request.AiChatRequest;
import io.lexiloom.application.ai.model.response.AiChatResponse;
import io.lexiloom.application.ai.model.search.DocChunkDocument;
import io.lexiloom.application.ai.model.vo.CitationVO;
import io.lexiloom.application.ai.search.CitationBuilder;
import io.lexiloom.application.ai.search.HybridSearchCommand;
import io.lexiloom.application.ai.search.HybridSearchQueryService;
import io.lexiloom.application.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ExtendWith(MockitoExtension.class)
class RagChatServiceTest {

  @Mock private HybridSearchQueryService hybridSearchQueryService;
  @Mock private CitationBuilder citationBuilder;
  @Mock private RagPromptTemplate ragPromptTemplate;
  @Mock private LlmGateway llmGateway;
  @Mock private ConversationSessionService sessionService;
  @Mock private SseEmitter sseEmitter;

  private AiProperties aiProperties;
  private DefaultRagChatService ragChatService;

  @BeforeEach
  void setUp() {
    aiProperties = new AiProperties();
    aiProperties.getRag().setTopK(3);
    aiProperties.getRag().setSimilarityThreshold(0.70);

    ragChatService =
        new DefaultRagChatService(
            hybridSearchQueryService,
            citationBuilder,
            ragPromptTemplate,
            llmGateway,
            sessionService,
            aiProperties);
  }

  @Test
  @DisplayName("验证同步对话入参防御校验")
  void testChatValidation() {
    assertThatThrownBy(() -> ragChatService.chat(null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("对话请求参数不能为空");

    assertThatThrownBy(() -> ragChatService.chat(new AiChatRequest("", 1L, null, null)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("提问内容不能为空");

    assertThatThrownBy(() -> ragChatService.chat(new AiChatRequest("提问", null, null, null)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("工作空间ID不能为空");
  }

  @Test
  @DisplayName("验证同步对话全流程：召回、重排、防幻觉提示词、模型调用与会话更新")
  void testChatSuccess() {
    AiChatRequest request = new AiChatRequest("解释下什么是 CQRS 架构？", 10L, "sess_1", true);

    when(sessionService.sessionExists("sess_1")).thenReturn(true);
    when(sessionService.getHistory("sess_1")).thenReturn(List.of());

    DocChunkDocument doc = new DocChunkDocument();
    doc.setId("chunk_1");
    doc.setContent("CQRS 是命令查询职责分离。");
    ScoredDocChunkVO scored = new ScoredDocChunkVO(doc, 0.95);
    when(hybridSearchQueryService.hybridSearch(any(HybridSearchCommand.class)))
        .thenReturn(List.of(scored));

    CitationVO citation =
        new CitationVO(
            1, 20L, "架构指南", "CQRS", "CQRS 是命令查询职责分离。", 0.95, 1, List.of(0f, 0f), "text", "a1");
    when(citationBuilder.buildCitations(any())).thenReturn(List.of(citation));

    LlmChatCommand chatCommand = LlmChatCommand.of("prompt", "system");
    when(ragPromptTemplate.buildChatCommand(
            eq(request.message()), eq(List.of(citation)), any(), eq(true)))
        .thenReturn(chatCommand);

    when(llmGateway.chat(chatCommand))
        .thenReturn(AiChatResponse.of("CQRS 将系统的读写操作分离。", "深度分析用户意图...", List.of(), 120L));

    AiChatResponse response = ragChatService.chat(request);

    assertThat(response.content()).isEqualTo("CQRS 将系统的读写操作分离。");
    assertThat(response.thinkingContent()).isEqualTo("深度分析用户意图...");
    assertThat(response.citations()).hasSize(1);
    assertThat(response.sessionId()).isEqualTo("sess_1");

    verify(sessionService).appendUserMessage(eq("sess_1"), eq(request.message()), eq(10L), any());
    verify(sessionService)
        .appendAssistantMessage(
            eq("sess_1"), eq("CQRS 将系统的读写操作分离。"), eq(List.of(citation)), eq("深度分析用户意图..."));
  }

  @Test
  @DisplayName("验证携带不存在或已过期的 sessionId 时抛出 SESSION_NOT_FOUND 异常")
  void testChatWithNonExistentSessionThrowsSessionNotFound() {
    AiChatRequest request = new AiChatRequest("提问", 10L, "sess_expired", false);
    when(sessionService.sessionExists("sess_expired")).thenReturn(false);

    assertThatThrownBy(() -> ragChatService.chat(request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("对话会话不存在或已过期");
  }

  @Test
  @DisplayName("验证流式 SSE 问答：优先下发 citation 事件，接着流式下发 thinking/message 并完成")
  void testStreamChatSuccess() throws Exception {
    AiChatRequest request = new AiChatRequest("请描述微服务链路追踪", 10L, "sess_stream", true);

    when(sessionService.sessionExists("sess_stream")).thenReturn(true);
    when(sessionService.getHistory("sess_stream")).thenReturn(List.of());

    CitationVO citation =
        new CitationVO(
            1,
            30L,
            "观测指南",
            "Tracing",
            "链路追踪核心在于 TraceID 与 SpanID。",
            0.90,
            2,
            List.of(),
            "text",
            "a2");
    when(citationBuilder.buildCitations(any())).thenReturn(List.of(citation));

    LlmChatCommand chatCommand = LlmChatCommand.of("prompt", "system");
    when(ragPromptTemplate.buildChatCommand(any(), any(), any(), eq(true))).thenReturn(chatCommand);

    // 模拟 LLM 触发流式回调
    doAnswer(
            invocation -> {
              LlmStreamCallback callback = invocation.getArgument(1);
              callback.onThinking("思考：需要结合 OpenTelemetry 标准解答。");
              callback.onMessage("微服务");
              callback.onMessage("链路追踪技术...");
              callback.onComplete();
              return null;
            })
        .when(llmGateway)
        .streamChat(eq(chatCommand), any(LlmStreamCallback.class));

    ragChatService.streamChat(request, sseEmitter);

    // 验证事件推送顺序与次数：
    // 1 次 citation 优先推送
    // 1 次 thinking 推送
    // 2 次 message 推送
    // 1 次 done 推送
    // 总共 5 次 send 调用
    verify(sseEmitter, times(5)).send(any(SseEmitter.SseEventBuilder.class));
    verify(sseEmitter).complete();

    // 验证会话持久化 (包含视觉溯源引用卡片与思考推理过程)
    verify(sessionService)
        .appendUserMessage(eq("sess_stream"), eq(request.message()), eq(10L), any());
    verify(sessionService)
        .appendAssistantMessage(
            eq("sess_stream"),
            eq("微服务链路追踪技术..."),
            eq(List.of(citation)),
            eq("思考：需要结合 OpenTelemetry 标准解答。"));
  }

  @Test
  @DisplayName("验证流式 SSE 异常时调用 emitter.completeWithError")
  void testStreamChatError() {
    AiChatRequest request = new AiChatRequest("测试异常", 10L, "sess_err", false);

    when(sessionService.sessionExists("sess_err")).thenReturn(true);
    when(sessionService.getHistory("sess_err")).thenReturn(List.of());
    when(citationBuilder.buildCitations(any())).thenReturn(List.of());

    LlmChatCommand chatCommand = LlmChatCommand.of("prompt");
    when(ragPromptTemplate.buildChatCommand(any(), any(), any(), eq(false)))
        .thenReturn(chatCommand);

    RuntimeException simulatedException = new RuntimeException("API 调用限流");
    doAnswer(
            invocation -> {
              LlmStreamCallback callback = invocation.getArgument(1);
              callback.onError(simulatedException);
              return null;
            })
        .when(llmGateway)
        .streamChat(eq(chatCommand), any(LlmStreamCallback.class));

    ragChatService.streamChat(request, sseEmitter);

    verify(sseEmitter).completeWithError(simulatedException);
  }
}
