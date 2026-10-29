package io.lexiloom.application.ai.controller;

import io.lexiloom.application.ai.listener.DocVectorSyncListener;
import io.lexiloom.application.ai.model.request.AiChatRequest;
import io.lexiloom.application.ai.model.request.ApiKeyTokenUsageQueryRequest;
import io.lexiloom.application.ai.model.request.ConversationSessionCreateRequest;
import io.lexiloom.application.ai.model.response.AiChatResponse;
import io.lexiloom.application.ai.model.response.AiReindexResponse;
import io.lexiloom.application.ai.model.response.ApiKeyTokenUsageResponse;
import io.lexiloom.application.ai.model.response.TokenUsageStatsResponse;
import io.lexiloom.application.ai.model.search.DocChunkDocument;
import io.lexiloom.application.ai.model.vo.ConversationMessageVO;
import io.lexiloom.application.ai.model.vo.ConversationSessionVO;
import io.lexiloom.application.ai.model.vo.DocChunkVO;
import io.lexiloom.application.ai.rag.ConversationSessionService;
import io.lexiloom.application.ai.rag.RagChatService;
import io.lexiloom.application.ai.repository.DocChunkRepository;
import io.lexiloom.application.ai.service.AiAccessSecurityValidator;
import io.lexiloom.application.ai.service.AiStatsQueryService;
import io.lexiloom.application.ai.trace.RagTracePersistenceService;
import io.lexiloom.application.ai.trace.entity.RagTraceDetailEntity;
import io.lexiloom.application.ai.trace.entity.RagTraceEntity;
import io.lexiloom.application.ai.trace.model.RagFeedbackRequest;
import io.lexiloom.application.ai.trace.model.RagTracePageRequest;
import io.lexiloom.application.exception.ErrorCode;
import io.lexiloom.application.common.LoginUserAuthentication;
import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.document.api.DocumentQueryApi;
import io.lexiloom.application.document.api.dto.DocumentDTO;
import io.lexiloom.application.document.api.dto.DocumentStateEnum;
import io.lexiloom.application.exception.BusinessException;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 智能问答与 RAG 检索增强 REST 控制器 (AiChatController)
 *
 * <p>严格遵循 Controller 纯粹性架构原则：仅负责参数校验与请求转发，无事务负担，不操作底层数据库实体直接转换。 提供流式 SSE
 * 问答通道、同步阻塞问答、多会话管理与历史回溯、单文档原子切块详情巡检与索引重建接口。
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiChatController {

  private static final long SSE_TIMEOUT_MS = 180_000L; // 3 分钟默认流式超时

  private final RagChatService ragChatService;
  private final ConversationSessionService conversationSessionService;
  private final DocumentQueryApi documentQueryApi;
  private final DocChunkRepository docChunkRepository;
  private final DocVectorSyncListener docVectorSyncListener;
  private final AiAccessSecurityValidator accessSecurityValidator;
  private final RagTracePersistenceService ragTracePersistenceService;
  private final AiStatsQueryService aiStatsQueryService;

  /**
   * SSE 流式对话接口（核心端点）
   *
   * <p>按序流式推送事件：
   *
   * <ul>
   *   <li>{@code event: citation} 视觉溯源几何坐标引用数组
   *   <li>{@code event: thinking} 深度推理思维链 Token
   *   <li>{@code event: tools} 工具调用状态增量，数据以 {@code <tools>...</tools>} 包裹
   *   <li>{@code event: message} 回答正文增量 Token
   *   <li>{@code event: done} 对话生成完成标记
   * </ul>
   *
   * @param request AI 对话请求参数
   * @return SSE 异步发射器通道
   */
  @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamChat(@Valid @RequestBody AiChatRequest request) {
    accessSecurityValidator.assertCanAccessWorkspace(request.workspaceId());
    validateChatDocumentScope(request);

    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
    ragChatService.streamChat(request, emitter);
    return emitter;
  }

  /**
   * 同步阻塞式对话接口
   *
   * @param request AI 对话请求参数
   * @return 完整回复、思考过程、溯源引用与耗时统计
   */
  @PostMapping("/chat")
  public AiChatResponse chat(@Valid @RequestBody AiChatRequest request) {
    accessSecurityValidator.assertCanAccessWorkspace(request.workspaceId());
    validateChatDocumentScope(request);

    return ragChatService.chat(request);
  }

  /**
   * 提交对指定 RAG 问答生成的评估反馈 (点赞/点踩、结构化归因标签与批注)
   *
   * @param traceId 链路跟踪唯一标识
   * @param request 反馈请求入参
   * @return 无内容 204
   */
  @PostMapping("/traces/{traceId}/feedback")
  public ResponseEntity<Void> recordFeedback(
      @PathVariable("traceId") String traceId, @Valid @RequestBody RagFeedbackRequest request) {
    Long userId = getCurrentUserId();
    ragTracePersistenceService.recordFeedback(traceId, request, userId);
    return ResponseEntity.noContent().build();
  }

  /**
   * 分页查询指定工作空间下的 RAG 问答观测链路快照 (继承 BasePageRequest)
   *
   * @param request 分页查询参数
   * @return 链路快照分页数据
   */
  @GetMapping("/traces")
  public Page<RagTraceEntity> pageTraces(@Valid RagTracePageRequest request) {
    if (request != null && request.getWorkspaceId() != null) {
      accessSecurityValidator.assertCanAccessWorkspace(request.getWorkspaceId());
    }
    return ragTracePersistenceService.pageTraces(request);
  }

  /**
   * 查询指定 RAG 观测链路的完整快照明细 (包含初筛切片生死因果与引用卡片)
   *
   * @param traceId 链路跟踪唯一标识
   * @return 链路快照明细
   */
  @GetMapping("/traces/{traceId}")
  public RagTraceDetailEntity getTraceDetail(@PathVariable("traceId") String traceId) {
    return ragTracePersistenceService
        .getDetailByTraceId(traceId)
        .orElseThrow(() -> BusinessException.notFound("未找到指定的链路明细记录"));
  }

  /**
   * 统计最近指定天数的 Token 消耗量指标（默认最近 7 天，返回全模型总计与各模型单独总计）
   *
   * @param workspaceId 可选的工作空间 ID (若传入则校验访问权限并按空间聚合)
   * @param days 统计天数范围 (若不传或非法则默认 7 天)
   * @return Token 消耗综合总计与模型明细
   */
  @GetMapping({"/token-stats", "/stats/tokens"})
  public TokenUsageStatsResponse getTokenUsageStats(
      @RequestParam(value = "workspaceId", required = false) Long workspaceId,
      @RequestParam(value = "days", required = false, defaultValue = "7") Integer days) {
    if (workspaceId != null) {
      accessSecurityValidator.assertCanAccessWorkspace(workspaceId);
    }
    return aiStatsQueryService.getTokenUsageStatsResponse(workspaceId, days);
  }

  /**
   * 统计每个或指定 API Key 的 Token 消耗用量趋势（支持按日 DAILY 或按月 MONTHLY 统计）
   *
   * @param request 查询参数 (包含可选 apiKey, period=DAILY/MONTHLY, days, months, workspaceId)
   * @return API Key Token 消耗趋势与分布统计
   */
  @GetMapping({"/stats/tokens/api-keys", "/token-stats/api-keys"})
  public ApiKeyTokenUsageResponse getApiKeyTokenUsageStats(
      @Valid ApiKeyTokenUsageQueryRequest request) {
    if (request != null && request.workspaceId() != null) {
      accessSecurityValidator.assertCanAccessWorkspace(request.workspaceId());
    }
    return aiStatsQueryService.getApiKeyTokenUsageStats(request);
  }

  /**
   * 查询指定工作空间下当前登录用户的会话列表（按最后更新时间倒序排序）
   *
   * @param workspaceId 工作空间主键 ID
   * @return 会话元数据列表
   */
  @GetMapping("/sessions")
  public List<ConversationSessionVO> listSessions(@RequestParam("workspaceId") Long workspaceId) {
    if (workspaceId == null) {
      throw BusinessException.badRequest("工作空间ID不能为空");
    }
    accessSecurityValidator.assertCanAccessWorkspace(workspaceId);
    Long userId = getCurrentUserId();
    return conversationSessionService.listSessions(workspaceId, userId);
  }

  /**
   * 显式创建新对话会话
   *
   * @param request 创建会话参数
   * @return 新建会话元数据
   */
  @PostMapping("/sessions")
  public ConversationSessionVO createSession(
      @Valid @RequestBody ConversationSessionCreateRequest request) {
    accessSecurityValidator.assertCanAccessWorkspace(request.workspaceId());
    Long userId = getCurrentUserId();
    return conversationSessionService.createSession(request.workspaceId(), userId, request.title());
  }

  /**
   * 获取指定会话的完整历史消息列表（用于前端聊天窗口展示）
   *
   * @param sessionId 会话唯一标识
   * @return 完整历史消息列表
   */
  @GetMapping("/sessions/{sessionId}/messages")
  public List<ConversationMessageVO> getSessionMessages(
      @PathVariable("sessionId") String sessionId) {
    if (!conversationSessionService.sessionExists(sessionId)) {
      throw new BusinessException(ErrorCode.Ai.SESSION_NOT_FOUND);
    }
    conversationSessionService
        .getSessionMeta(sessionId)
        .ifPresent(meta -> accessSecurityValidator.assertCanAccessWorkspace(meta.workspaceId()));

    return conversationSessionService.getFullHistory(sessionId);
  }

  /**
   * 删除指定会话（销毁消息、元数据及索引）
   *
   * @param sessionId 会话唯一标识
   * @return 无内容 204
   */
  @DeleteMapping("/sessions/{sessionId}")
  public ResponseEntity<Void> deleteSession(@PathVariable("sessionId") String sessionId) {
    if (!conversationSessionService.sessionExists(sessionId)) {
      throw new BusinessException(ErrorCode.Ai.SESSION_NOT_FOUND);
    }
    conversationSessionService
        .getSessionMeta(sessionId)
        .ifPresent(meta -> accessSecurityValidator.assertCanAccessWorkspace(meta.workspaceId()));

    Long userId = SecurityHolder.getLoginUser().map(LoginUserAuthentication::userId).orElse(null);
    conversationSessionService.deleteSession(sessionId, userId);
    return ResponseEntity.noContent().build();
  }

  /**
   * 查看指定文档的 DeepDoc 原子切块详情、上下文帽子及视觉物理坐标
   *
   * @param docId 文档主键 ID
   * @return 按原子切片物理序号升序排列的切块值对象列表
   */
  @GetMapping("/chunks/{docId}")
  public List<DocChunkVO> getDocChunks(@PathVariable("docId") Long docId) {
    DocumentDTO doc =
        documentQueryApi
            .findDocumentById(docId)
            .orElseThrow(() -> BusinessException.notFound("文档不存在或已被删除"));

    accessSecurityValidator.assertCanAccessWorkspace(doc.workSpaceId());
    accessSecurityValidator.assertCanReadDocument(doc);

    List<DocChunkDocument> chunks =
        docChunkRepository.findByWorkspaceIdAndDocumentIdOrderByChunkIndexAsc(
            String.valueOf(doc.workSpaceId()), String.valueOf(docId));

    return chunks.stream().map(c -> DocChunkVO.fromDocument(c, null)).toList();
  }

  /**
   * 手动触发单文档重新执行上下文帽子组装与向量化入库
   *
   * @param docId 文档主键 ID
   * @return 重建结果统计
   */
  @PostMapping("/reindex/{docId}")
  public AiReindexResponse reindexDocument(@PathVariable("docId") Long docId) {
    DocumentDTO doc =
        documentQueryApi
            .findDocumentById(docId)
            .orElseThrow(() -> BusinessException.notFound("文档不存在或已被删除"));

    accessSecurityValidator.assertCanAccessWorkspace(doc.workSpaceId());

    if (doc.status() != DocumentStateEnum.APPROVED) {
      throw BusinessException.badRequest("仅允许对已审批通过(APPROVED)状态的文档重建向量索引");
    }

    docVectorSyncListener.syncDocumentChunks(docId);

    long count =
        docChunkRepository.countByWorkspaceIdAndDocumentId(
            String.valueOf(doc.workSpaceId()), String.valueOf(docId));

    return AiReindexResponse.success(docId, count);
  }

  private void validateChatDocumentScope(AiChatRequest request) {
    if (request != null && request.documentId() != null) {
      DocumentDTO doc =
          documentQueryApi
              .findDocumentById(request.documentId())
              .orElseThrow(() -> BusinessException.notFound("指定的提问文档不存在: " + request.documentId()));
      if (!request.workspaceId().equals(doc.workSpaceId())) {
        throw BusinessException.badRequest("指定文档不属于当前工作空间");
      }
      accessSecurityValidator.assertCanReadDocument(doc);
    }
  }

  private Long getCurrentUserId() {
    return SecurityHolder.getLoginUser().map(LoginUserAuthentication::userId).orElse(null);
  }
}
