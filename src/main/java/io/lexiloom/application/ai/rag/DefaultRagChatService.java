package io.lexiloom.application.ai.rag;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ChatMessage;
import io.lexiloom.application.ai.gateway.llm.LlmGateway;
import io.lexiloom.application.ai.gateway.rerank.ScoredDocChunkVO;
import io.lexiloom.application.ai.model.request.AiChatRequest;
import io.lexiloom.application.ai.model.response.AiChatChunkResponse;
import io.lexiloom.application.ai.model.response.AiChatResponse;
import io.lexiloom.application.ai.model.vo.CitationVO;
import io.lexiloom.application.ai.model.vo.ConversationSessionVO;
import io.lexiloom.application.ai.rag.RagToolChatExecutor.ToolingChatResult;
import io.lexiloom.application.ai.search.CitationBuilder;
import io.lexiloom.application.ai.search.HybridSearchCommand;
import io.lexiloom.application.ai.search.HybridSearchQueryService;
import io.lexiloom.application.ai.tool.KnowledgeSearchTool;
import io.lexiloom.application.ai.trace.RagTraceHolder;
import io.lexiloom.application.ai.trace.RagTracePersistenceService;
import io.lexiloom.application.ai.trace.RagTraceable;
import io.lexiloom.application.ai.trace.model.RagTraceContext;
import io.lexiloom.application.exception.ErrorCode;
import io.lexiloom.application.common.LoginUserAuthentication;
import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.enums.WorkSpaceRoleEnum;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.api.WorkSpaceMemberQueryService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * RAG 智能问答与检索增强编排核心服务默认实现 (DefaultRagChatService)
 *
 * <p>基于 CQRS 架构防腐层原则，统筹知识库混合检索、重排筛选、Prompt 上下文注入、多轮会话窗口及大模型交互。
 */
@Slf4j
@Service
@Primary
public class DefaultRagChatService implements RagChatService {

  private final KnowledgeRetrievalQueryService knowledgeRetrievalQueryService;
  private final CitationBuilder citationBuilder;
  private final RagPromptTemplate ragPromptTemplate;
  private final LlmGateway llmGateway;
  private final ConversationSessionService conversationSessionService;
  private final AiProperties aiProperties;
  @Nullable private final WorkSpaceMemberQueryService workSpaceMemberQueryService;
  @Nullable private final RagTracePersistenceService ragTracePersistenceService;
  @Nullable private final KnowledgeSearchTool knowledgeSearchTool;
  @Nullable private final ObjectMapper objectMapper;
  private final RagToolChatExecutor toolChatExecutor;

  @Autowired
  public DefaultRagChatService(
      KnowledgeRetrievalQueryService knowledgeRetrievalQueryService,
      CitationBuilder citationBuilder,
      RagPromptTemplate ragPromptTemplate,
      LlmGateway llmGateway,
      ConversationSessionService conversationSessionService,
      AiProperties aiProperties,
      @Nullable WorkSpaceMemberQueryService workSpaceMemberQueryService,
      @Nullable RagTracePersistenceService ragTracePersistenceService,
      @Nullable KnowledgeSearchTool knowledgeSearchTool,
      @Nullable ObjectMapper objectMapper) {
    this.knowledgeRetrievalQueryService = knowledgeRetrievalQueryService;
    this.citationBuilder = citationBuilder;
    this.ragPromptTemplate = ragPromptTemplate;
    this.llmGateway = llmGateway;
    this.conversationSessionService = conversationSessionService;
    this.aiProperties = aiProperties;
    this.workSpaceMemberQueryService = workSpaceMemberQueryService;
    this.ragTracePersistenceService = ragTracePersistenceService;
    this.knowledgeSearchTool = knowledgeSearchTool;
    this.objectMapper = objectMapper;
    this.toolChatExecutor = new RagToolChatExecutor(llmGateway, knowledgeSearchTool, objectMapper);
  }

  public DefaultRagChatService(
      HybridSearchQueryService hybridSearchQueryService,
      CitationBuilder citationBuilder,
      RagPromptTemplate ragPromptTemplate,
      LlmGateway llmGateway,
      ConversationSessionService conversationSessionService,
      AiProperties aiProperties,
      @Nullable WorkSpaceMemberQueryService workSpaceMemberQueryService,
      @Nullable RagTracePersistenceService ragTracePersistenceService) {
    this(
        new DefaultKnowledgeRetrievalQueryService(
            hybridSearchQueryService, aiProperties, workSpaceMemberQueryService),
        citationBuilder,
        ragPromptTemplate,
        llmGateway,
        conversationSessionService,
        aiProperties,
        workSpaceMemberQueryService,
        ragTracePersistenceService,
        null,
        null);
  }

  public DefaultRagChatService(
      KnowledgeRetrievalQueryService knowledgeRetrievalQueryService,
      CitationBuilder citationBuilder,
      RagPromptTemplate ragPromptTemplate,
      LlmGateway llmGateway,
      ConversationSessionService conversationSessionService,
      AiProperties aiProperties,
      @Nullable WorkSpaceMemberQueryService workSpaceMemberQueryService,
      @Nullable RagTracePersistenceService ragTracePersistenceService) {
    this(
        knowledgeRetrievalQueryService,
        citationBuilder,
        ragPromptTemplate,
        llmGateway,
        conversationSessionService,
        aiProperties,
        workSpaceMemberQueryService,
        ragTracePersistenceService,
        null,
        null);
  }

  public DefaultRagChatService(
      HybridSearchQueryService hybridSearchQueryService,
      CitationBuilder citationBuilder,
      RagPromptTemplate ragPromptTemplate,
      LlmGateway llmGateway,
      ConversationSessionService conversationSessionService,
      AiProperties aiProperties,
      @Nullable WorkSpaceMemberQueryService workSpaceMemberQueryService) {
    this(
        hybridSearchQueryService,
        citationBuilder,
        ragPromptTemplate,
        llmGateway,
        conversationSessionService,
        aiProperties,
        workSpaceMemberQueryService,
        null);
  }

  public DefaultRagChatService(
      HybridSearchQueryService hybridSearchQueryService,
      CitationBuilder citationBuilder,
      RagPromptTemplate ragPromptTemplate,
      LlmGateway llmGateway,
      ConversationSessionService conversationSessionService,
      AiProperties aiProperties) {
    this(
        hybridSearchQueryService,
        citationBuilder,
        ragPromptTemplate,
        llmGateway,
        conversationSessionService,
        aiProperties,
        null,
        null);
  }

  @Override
  @RagTraceable
  public AiChatResponse chat(AiChatRequest request) {
    validateRequest(request);

    long startMs = System.currentTimeMillis();
    String sessionId = resolveSessionId(request.sessionId(), request.workspaceId());
    List<ChatMessage> history = conversationSessionService.getHistory(sessionId);

    RagTraceContext traceContext = RagTraceHolder.get();
    if (traceContext != null) {
      traceContext.recordSessionId(sessionId);
    }
    String traceId = traceContext != null ? traceContext.getTraceId() : null;

    // 1. 执行多租户隔离的双路混合检索与可选重排
    List<CitationVO> citations = performSearchAndBuildCitations(request);
    // 2. 组装注入防幻觉约束与参考资料的提示词命令
    LlmChatCommand chatCommand =
        ragPromptTemplate.buildChatCommand(
            request.message(), citations, history, request.isThinkingEnabled());

    // 3. 调用大语言模型执行同步对话（或带知识检索工具的多轮同步调用）
    log.info(
        "【RagChatService】发起同步 RAG 对话: wsId={}, sessionId={}, 提问长度={}, 召回引用数={}",
        request.workspaceId(),
        sessionId,
        request.message() != null ? request.message().length() : 0,
        citations.size());
    long llmStart = System.currentTimeMillis();
    ToolingChatResult toolingResult =
        toolChatExecutor.execute(chatCommand, history, request.workspaceId(), citations);
    AiChatResponse llmResponse = toolingResult.response();
    citations = toolingResult.citations();
    if (traceContext != null) {
      traceContext.recordCitations(citations);
    }
    long llmCost = System.currentTimeMillis() - llmStart;
    if (traceContext != null) {
      String modelName =
          aiProperties != null ? aiProperties.resolveEffectiveModelName() : "deepseek-chat";
      traceContext.recordAnswer(llmResponse.content(), modelName);
      traceContext.recordGenerationTime(llmCost);
      if (llmResponse.promptTokens() != null || llmResponse.completionTokens() != null) {
        traceContext.recordLlmTokens(
            llmResponse.promptTokens() != null ? llmResponse.promptTokens().longValue() : null,
            llmResponse.completionTokens() != null
                ? llmResponse.completionTokens().longValue()
                : null);
      }
    }

    long latencyMs = System.currentTimeMillis() - startMs;
    log.info(
        "【RagChatService】同步 RAG 对话完成: wsId={}, sessionId={}, 耗时={}ms, 回复长度={}, Token=[prompt={},"
            + " completion={}, total={}]",
        request.workspaceId(),
        sessionId,
        latencyMs,
        llmResponse.content() != null ? llmResponse.content().length() : 0,
        llmResponse.promptTokens(),
        llmResponse.completionTokens(),
        llmResponse.totalTokens());

    // 4. 持久化多轮上下文消息及元数据更新 (完整保留正文、溯源卡片与思维链)
    Long currentUserId =
        SecurityHolder.getLoginUser().map(LoginUserAuthentication::userId).orElse(null);
    conversationSessionService.appendUserMessage(
        sessionId, request.message(), request.workspaceId(), currentUserId);
    if (StrUtil.isNotBlank(llmResponse.content())) {
      conversationSessionService.appendAssistantMessage(
          sessionId, llmResponse.content(), citations, llmResponse.thinkingContent());
    }

    // 5. 组装包含精确视觉溯源坐标、会话 ID 及 traceId 的最终响应
    return AiChatResponse.of(
        llmResponse.content(),
        llmResponse.thinkingContent(),
        citations,
        latencyMs,
        sessionId,
        traceId,
        llmResponse.promptTokens(),
        llmResponse.completionTokens(),
        llmResponse.totalTokens());
  }

  @Override
  @RagTraceable
  public void streamChat(AiChatRequest request, SseEmitter emitter) {
    if (emitter == null) {
      return;
    }

    try {
      validateRequest(request);
    } catch (Exception e) {
      RagStreamingChatSession.sendSseEvent(emitter, "error", e.getMessage());
      emitter.complete();
      return;
    }

    long startMs = System.currentTimeMillis();
    String sessionId;
    try {
      sessionId = resolveSessionId(request.sessionId(), request.workspaceId());
    } catch (Exception e) {
      RagStreamingChatSession.sendSseEvent(emitter, "error", e.getMessage());
      emitter.complete();
      return;
    }
    List<ChatMessage> history = conversationSessionService.getHistory(sessionId);

    RagTraceContext traceContext = RagTraceHolder.get();
    if (traceContext != null) {
      traceContext.recordSessionId(sessionId);
    }
    String traceId = traceContext != null ? traceContext.getTraceId() : null;

    // 1. 执行多租户隔离的双路检索并生成结构化引用源
    List<CitationVO> citations = performSearchAndBuildCitations(request);
    if (traceContext != null) {
      traceContext.recordCitations(citations);
    }

    // 2. 核心体验保障：优先向客户端推送 citation 事件，使前端阅读器立即完成原件页面切片划线高亮
    RagStreamingChatSession.sendSseEvent(
        emitter, "citation", AiChatChunkResponse.citation(citations, sessionId, traceId));

    // 3. 组装 Prompt 命令
    LlmChatCommand chatCommand =
        ragPromptTemplate.buildChatCommand(
            request.message(), citations, history, request.isThinkingEnabled());

    log.info(
        "【RagChatService】发起流式 RAG 对话: wsId={}, sessionId={}, 提问长度={}, 召回引用数={}",
        request.workspaceId(),
        sessionId,
        request.message() != null ? request.message().length() : 0,
        citations.size());

    new RagStreamingChatSession(
            request,
            emitter,
            chatCommand,
            history,
            citations,
            sessionId,
            traceId,
            traceContext,
            startMs,
            llmGateway,
            conversationSessionService,
            aiProperties,
            ragTracePersistenceService,
            knowledgeSearchTool,
            objectMapper)
        .start();
  }

  private List<CitationVO> performSearchAndBuildCitations(AiChatRequest request) {
    int requestedTopK = request.topK() != null ? request.topK() : aiProperties.getRag().getTopK();
    int minTopK = aiProperties.getRag().getMinTopK();
    int maxTopK = aiProperties.getRag().getMaxTopK();
    int topK = Math.clamp(requestedTopK, minTopK, maxTopK);
    double minScore = aiProperties.getRag().getSimilarityThreshold();

    Long currentUserId =
        SecurityHolder.getLoginUser().map(LoginUserAuthentication::userId).orElse(null);
    boolean isAdmin =
        currentUserId != null
            && ((SecurityHolder.getLoginUser()
                    .map(u -> u.roles().contains("ADMIN") || u.roles().contains("SUPER_ADMIN"))
                    .orElse(false))
                || (workSpaceMemberQueryService != null
                    && workSpaceMemberQueryService.hasRole(
                        request.workspaceId(), currentUserId, WorkSpaceRoleEnum.ADMIN)));

    HybridSearchCommand searchCommand =
        new HybridSearchCommand(
            request.workspaceId(),
            request.message(),
            null,
            topK,
            minScore,
            request.documentId(),
            List.of(),
            null,
            currentUserId,
            isAdmin,
            request.weights(),
            request.nativeHybridSearch());

    List<ScoredDocChunkVO> scoredChunks =
        knowledgeRetrievalQueryService.retrieveScoredChunks(searchCommand);
    return citationBuilder.buildCitations(scoredChunks);
  }

  private String resolveSessionId(String providedSessionId, Long workspaceId) {
    if (StrUtil.isNotBlank(providedSessionId)) {
      String sId = providedSessionId.trim();
      if (!conversationSessionService.sessionExists(sId)) {
        throw new BusinessException(ErrorCode.Ai.SESSION_NOT_FOUND);
      }
      conversationSessionService
          .getSessionMeta(sId)
          .ifPresent(
              meta -> {
                if (meta.workspaceId() != null && !meta.workspaceId().equals(workspaceId)) {
                  throw BusinessException.badRequest("该会话不属于当前工作空间");
                }
              });
      return sId;
    }
    Long currentUserId =
        SecurityHolder.getLoginUser().map(LoginUserAuthentication::userId).orElse(null);
    ConversationSessionVO newSession =
        conversationSessionService.createSession(workspaceId, currentUserId, null);
    return newSession.sessionId();
  }

  private void validateRequest(AiChatRequest request) {
    if (request == null) {
      throw BusinessException.badRequest("对话请求参数不能为空");
    }
    if (StrUtil.isBlank(request.message())) {
      throw BusinessException.badRequest("提问内容不能为空");
    }
    if (request.workspaceId() == null) {
      throw BusinessException.badRequest("工作空间ID不能为空");
    }
  }
}
