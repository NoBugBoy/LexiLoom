package io.lexiloom.application.ai.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lexiloom.application.ai.listener.DocVectorSyncListener;
import io.lexiloom.application.ai.model.request.AiChatRequest;
import io.lexiloom.application.ai.model.request.ApiKeyTokenUsageQueryRequest;
import io.lexiloom.application.ai.model.request.ConversationSessionCreateRequest;
import io.lexiloom.application.ai.model.response.AiChatResponse;
import io.lexiloom.application.ai.model.response.AiReindexResponse;
import io.lexiloom.application.ai.model.response.ApiKeyTokenUsageResponse;
import io.lexiloom.application.ai.model.search.DocChunkDocument;
import io.lexiloom.application.ai.model.vo.ConversationMessageVO;
import io.lexiloom.application.ai.model.vo.ConversationSessionVO;
import io.lexiloom.application.ai.model.vo.DocChunkVO;
import io.lexiloom.application.ai.rag.ConversationSessionService;
import io.lexiloom.application.ai.rag.RagChatService;
import io.lexiloom.application.ai.repository.DocChunkRepository;
import io.lexiloom.application.ai.service.AiAccessSecurityValidator;
import io.lexiloom.application.ai.trace.RagTracePersistenceService;
import io.lexiloom.application.ai.trace.entity.RagTraceDetailEntity;
import io.lexiloom.application.ai.trace.entity.RagTraceEntity;
import io.lexiloom.application.ai.trace.model.RagFeedbackRequest;
import io.lexiloom.application.ai.trace.model.RagTracePageRequest;
import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.document.api.DocumentQueryApi;
import io.lexiloom.application.document.api.dto.DocumentDTO;
import io.lexiloom.application.document.api.dto.DocumentStateEnum;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
import io.lexiloom.application.enums.WorkSpaceRoleEnum;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.api.WorkSpaceMemberQueryService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ExtendWith(MockitoExtension.class)
class AiChatControllerTest {

  @Mock private RagChatService ragChatService;
  @Mock private ConversationSessionService conversationSessionService;
  @Mock private DocumentQueryApi documentQueryApi;
  @Mock private DocChunkRepository docChunkRepository;
  @Mock private DocVectorSyncListener docVectorSyncListener;
  @Mock private WorkSpaceMemberQueryService workSpaceMemberQueryService;
  @Mock private RagTracePersistenceService ragTracePersistenceService;
  @Mock private io.lexiloom.application.ai.service.AiStatsQueryService aiStatsQueryService;

  private AiChatController controller;

  @BeforeEach
  void setUp() {
    controller =
        new AiChatController(
            ragChatService,
            conversationSessionService,
            documentQueryApi,
            docChunkRepository,
            docVectorSyncListener,
            new AiAccessSecurityValidator(workSpaceMemberQueryService),
            ragTracePersistenceService,
            aiStatsQueryService);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("验证 POST /api/ai/chat 同步阻塞问答接口转发")
  void testChatEndpoint() {
    AiChatRequest request = new AiChatRequest("提问内容", 1L, "sess_1", true);
    AiChatResponse expectedResponse = AiChatResponse.of("回答内容", "思考过程", List.of(), 100L, "sess_1");

    when(ragChatService.chat(request)).thenReturn(expectedResponse);

    AiChatResponse actual = controller.chat(request);

    assertThat(actual).isEqualTo(expectedResponse);
    verify(ragChatService).chat(request);
  }

  @Test
  @DisplayName("验证 POST /api/ai/chat/stream SSE 流式问答接口发射器创建与委派")
  void testStreamChatEndpoint() {
    AiChatRequest request = new AiChatRequest("流式提问", 1L, null, false);

    SseEmitter emitter = controller.streamChat(request);

    assertThat(emitter).isNotNull();
    verify(ragChatService).streamChat(eq(request), any(SseEmitter.class));
  }

  @Test
  @DisplayName("验证工作空间多租户越权拦截：非空间成员禁止访问问答")
  void testWorkspaceUnauthorizedAccess() {
    SecurityHolder.setAuthentication(999L);
    when(workSpaceMemberQueryService.isMember(10L, 999L)).thenReturn(false);

    AiChatRequest request = new AiChatRequest("测试越权", 10L, null, false);

    assertThatThrownBy(() -> controller.chat(request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("您不是该工作空间成员");
  }

  @Test
  @DisplayName("验证指定 documentId 跨空间归属校验拦截")
  void testChatDocumentScopeValidation() {
    DocumentDTO otherWsDoc =
        new DocumentDTO(
            50L,
            2L, // 属于空间 2
            null,
            "其他空间文档",
            null,
            null,
            DocumentStateEnum.APPROVED,
            null,
            null,
            null,
            null,
            null,
            1,
            "v1",
            1,
            1,
            null,
            0,
            0,
            1L,
            null,
            null);
    when(documentQueryApi.findDocumentById(50L)).thenReturn(Optional.of(otherWsDoc));

    AiChatRequest request = new AiChatRequest("问题", 1L, null, false, 50L);

    assertThatThrownBy(() -> controller.chat(request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("指定文档不属于当前工作空间");
  }

  @Test
  @DisplayName("验证 GET /api/ai/chunks/{docId} 查询原子切块详情与坐标数据")
  void testGetDocChunksSuccess() {
    Long docId = 200L;
    DocumentDTO mockDoc =
        new DocumentDTO(
            docId,
            1L,
            10L,
            "技术文档",
            "摘要",
            "正文",
            DocumentStateEnum.APPROVED,
            null,
            null,
            null,
            null,
            "file.pdf",
            1,
            "v1.0",
            1,
            1,
            null,
            100,
            0,
            1L,
            null,
            null);

    when(documentQueryApi.findDocumentById(docId)).thenReturn(Optional.of(mockDoc));

    DocChunkDocument chunk1 = new DocChunkDocument();
    chunk1.setId("200_0");
    chunk1.setDocumentId("200");
    chunk1.setChunkIndex(0);
    chunk1.setContent("第一段原文");
    chunk1.setContextPrefix("[文档: 《技术文档》] > [正文]");
    chunk1.setPage(1);
    chunk1.setBbox(List.of(10f, 20f, 30f, 40f));
    chunk1.setChunkType("text");
    chunk1.setAnchor("段落_1");

    when(docChunkRepository.findByWorkspaceIdAndDocumentIdOrderByChunkIndexAsc("1", "200"))
        .thenReturn(List.of(chunk1));

    List<DocChunkVO> chunks = controller.getDocChunks(docId);

    assertThat(chunks).hasSize(1);
    DocChunkVO vo = chunks.get(0);
    assertThat(vo.id()).isEqualTo("200_0");
    assertThat(vo.documentId()).isEqualTo(200L);
    assertThat(vo.chunkIndex()).isEqualTo(0);
    assertThat(vo.content()).isEqualTo("第一段原文");
    assertThat(vo.contextPrefix()).isEqualTo("[文档: 《技术文档》] > [正文]");
    assertThat(vo.page()).isEqualTo(1);
    assertThat(vo.bbox()).containsExactly(10f, 20f, 30f, 40f);
  }

  @Test
  @DisplayName("验证 GET /api/ai/chunks/{docId} 文档不存在时抛出 404 异常")
  void testGetDocChunksNotFound() {
    when(documentQueryApi.findDocumentById(999L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.getDocChunks(999L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("文档不存在或已被删除");
  }

  @Test
  @DisplayName("验证 POST /api/ai/reindex/{docId} 手动触发切块组装与向量重构")
  void testReindexDocumentSuccess() {
    Long docId = 300L;
    DocumentDTO mockDoc =
        new DocumentDTO(
            docId,
            1L,
            10L,
            "重构文档",
            "摘要",
            "正文",
            DocumentStateEnum.APPROVED,
            null,
            null,
            null,
            null,
            "test.md",
            1,
            "v1.0",
            1,
            1,
            null,
            200,
            0,
            1L,
            null,
            null);

    when(documentQueryApi.findDocumentById(docId)).thenReturn(Optional.of(mockDoc));
    when(docChunkRepository.countByWorkspaceIdAndDocumentId("1", "300")).thenReturn(8L);

    AiReindexResponse response = controller.reindexDocument(docId);

    assertThat(response.documentId()).isEqualTo(300L);
    assertThat(response.chunkCount()).isEqualTo(8L);
    assertThat(response.message()).contains("成功");

    verify(docVectorSyncListener).syncDocumentChunks(docId);
  }

  @Test
  @DisplayName("验证 POST /api/ai/reindex/{docId} 未审批通过文档禁止重建向量")
  void testReindexUnapprovedDocumentRejected() {
    Long docId = 301L;
    DocumentDTO mockDoc =
        new DocumentDTO(
            docId,
            1L,
            10L,
            "草稿文档",
            null,
            null,
            DocumentStateEnum.PENDING_APPROVAL,
            null,
            null,
            null,
            null,
            null,
            1,
            "v1",
            1,
            1,
            null,
            0,
            0,
            1L,
            null,
            null);

    when(documentQueryApi.findDocumentById(docId)).thenReturn(Optional.of(mockDoc));

    assertThatThrownBy(() -> controller.reindexDocument(docId))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("仅允许对已审批通过");
  }

  @Test
  @DisplayName("验证 GET /api/ai/sessions 获取工作空间会话列表")
  void testListSessionsSuccess() {
    SecurityHolder.setAuthentication(100L);
    when(workSpaceMemberQueryService.isMember(1L, 100L)).thenReturn(true);

    ConversationSessionVO s1 =
        new ConversationSessionVO(
            "s1", "标题1", 1L, 100L, 2, "2026-10-13 10:00:00", "2026-10-13 10:05:00");
    when(conversationSessionService.listSessions(1L, 100L)).thenReturn(List.of(s1));

    List<ConversationSessionVO> result = controller.listSessions(1L);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).sessionId()).isEqualTo("s1");
    assertThat(result.get(0).title()).isEqualTo("标题1");
  }

  @Test
  @DisplayName("验证 POST /api/ai/sessions 显式创建会话")
  void testCreateSessionSuccess() {
    SecurityHolder.setAuthentication(100L);
    when(workSpaceMemberQueryService.isMember(1L, 100L)).thenReturn(true);

    ConversationSessionCreateRequest req = new ConversationSessionCreateRequest(1L, "自定标题");
    ConversationSessionVO s =
        new ConversationSessionVO(
            "s_new", "自定标题", 1L, 100L, 0, "2026-10-13 12:00:00", "2026-10-13 12:00:00");
    when(conversationSessionService.createSession(1L, 100L, "自定标题")).thenReturn(s);

    ConversationSessionVO res = controller.createSession(req);

    assertThat(res.sessionId()).isEqualTo("s_new");
    assertThat(res.title()).isEqualTo("自定标题");
  }

  @Test
  @DisplayName("验证 GET /api/ai/sessions/{sessionId}/messages 获取完整历史")
  void testGetSessionMessagesSuccess() {
    when(conversationSessionService.sessionExists("s1")).thenReturn(true);
    ConversationSessionVO meta =
        new ConversationSessionVO(
            "s1", "标题", 1L, 100L, 2, "2026-10-13 10:00:00", "2026-10-13 10:05:00");
    when(conversationSessionService.getSessionMeta("s1")).thenReturn(Optional.of(meta));

    List<ConversationMessageVO> msgs =
        List.of(
            new ConversationMessageVO("user", "你好", "2026-10-13 10:00:00"),
            new ConversationMessageVO("assistant", "你好！", "2026-10-13 10:00:02"));
    when(conversationSessionService.getFullHistory("s1")).thenReturn(msgs);

    List<ConversationMessageVO> result = controller.getSessionMessages("s1");

    assertThat(result).hasSize(2);
    assertThat(result.get(0).content()).isEqualTo("你好");
  }

  @Test
  @DisplayName("验证 GET /api/ai/sessions/{sessionId}/messages 会话不存在抛出 404")
  void testGetSessionMessagesNotFound() {
    when(conversationSessionService.sessionExists("non_existent")).thenReturn(false);

    assertThatThrownBy(() -> controller.getSessionMessages("non_existent"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("对话会话不存在或已过期");
  }

  @Test
  @DisplayName("验证 DELETE /api/ai/sessions/{sessionId} 删除会话")
  void testDeleteSessionSuccess() {
    SecurityHolder.setAuthentication(100L);
    when(conversationSessionService.sessionExists("s1")).thenReturn(true);

    ResponseEntity<Void> response = controller.deleteSession("s1");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    verify(conversationSessionService).deleteSession("s1", 100L);
  }

  @Test
  @DisplayName("单文档问答权限：非创建者且非管理员访问 PRIVATE 文档被拦截")
  void testChatSingleDocumentPrivateDeniedForNonAuthorNonAdmin() {
    SecurityHolder.setAuthentication(100L); // 当前用户 100
    when(workSpaceMemberQueryService.isMember(1L, 100L)).thenReturn(true);
    when(workSpaceMemberQueryService.hasRole(1L, 100L, WorkSpaceRoleEnum.ADMIN)).thenReturn(false);

    DocumentDTO privateDoc =
        new DocumentDTO(
            88L,
            1L,
            null,
            "绝密文档",
            null,
            null,
            DocumentStateEnum.APPROVED,
            null,
            WorkSpaceAclEnum.PRIVATE,
            null,
            null,
            null,
            1,
            "v1",
            1,
            1,
            null,
            0,
            0,
            200L, // 创建者是 200，不是当前用户 100
            null,
            null);
    when(documentQueryApi.findDocumentById(88L)).thenReturn(Optional.of(privateDoc));

    AiChatRequest request = new AiChatRequest("提问绝密内容", 1L, null, false, 88L);

    assertThatThrownBy(() -> controller.chat(request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("您无权访问该私密文档");
  }

  @Test
  @DisplayName("单文档问答权限：文档创建者本人可正常与 PRIVATE 文档对话")
  void testChatSingleDocumentPrivateAllowedForAuthor() {
    SecurityHolder.setAuthentication(200L); // 当前用户 200（作者本人）
    when(workSpaceMemberQueryService.isMember(1L, 200L)).thenReturn(true);
    when(workSpaceMemberQueryService.hasRole(1L, 200L, WorkSpaceRoleEnum.ADMIN)).thenReturn(false);

    DocumentDTO privateDoc =
        new DocumentDTO(
            88L,
            1L,
            null,
            "绝密文档",
            null,
            null,
            DocumentStateEnum.APPROVED,
            null,
            WorkSpaceAclEnum.PRIVATE,
            null,
            null,
            null,
            1,
            "v1",
            1,
            1,
            null,
            0,
            0,
            200L, // 创建者是 200
            null,
            null);
    when(documentQueryApi.findDocumentById(88L)).thenReturn(Optional.of(privateDoc));
    AiChatResponse expectedResp = AiChatResponse.of("回复", null, List.of(), 50L);
    when(ragChatService.chat(any(AiChatRequest.class))).thenReturn(expectedResp);

    AiChatRequest request = new AiChatRequest("提问自己的绝密文档", 1L, null, false, 88L);
    AiChatResponse response = controller.chat(request);

    assertThat(response).isNotNull();
    verify(ragChatService).chat(request);
  }

  @Test
  @DisplayName("单文档问答权限：空间管理员可访问空间内任意成员的 PRIVATE 文档")
  void testChatSingleDocumentPrivateAllowedForAdmin() {
    SecurityHolder.setAuthentication(999L); // 当前用户 999 (ADMIN)
    when(workSpaceMemberQueryService.isMember(1L, 999L)).thenReturn(true);
    when(workSpaceMemberQueryService.hasRole(1L, 999L, WorkSpaceRoleEnum.ADMIN)).thenReturn(true);

    DocumentDTO privateDoc =
        new DocumentDTO(
            88L,
            1L,
            null,
            "绝密文档",
            null,
            null,
            DocumentStateEnum.APPROVED,
            null,
            WorkSpaceAclEnum.PRIVATE,
            null,
            null,
            null,
            1,
            "v1",
            1,
            1,
            null,
            0,
            0,
            200L, // 创建者是 200
            null,
            null);
    when(documentQueryApi.findDocumentById(88L)).thenReturn(Optional.of(privateDoc));
    AiChatResponse expectedResp = AiChatResponse.of("管理员回复", null, List.of(), 50L);
    when(ragChatService.chat(any(AiChatRequest.class))).thenReturn(expectedResp);

    AiChatRequest request = new AiChatRequest("管理员审计提问", 1L, null, false, 88L);
    AiChatResponse response = controller.chat(request);

    assertThat(response).isNotNull();
    verify(ragChatService).chat(request);
  }

  @Test
  @DisplayName("验证 POST /api/ai/traces/{traceId}/feedback 反馈接口正常委派并返回 204")
  void testRecordFeedbackEndpoint() {
    SecurityHolder.setAuthentication(123L);
    RagFeedbackRequest feedbackRequest =
        new RagFeedbackRequest(1, List.of("HELPFUL", "ACCURATE"), "回答非常清晰详实");

    ResponseEntity<Void> response = controller.recordFeedback("trace-abc-123", feedbackRequest);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(ragTracePersistenceService).recordFeedback("trace-abc-123", feedbackRequest, 123L);
  }

  @Test
  @DisplayName("验证 GET /api/ai/traces 观测链路分页查询")
  void testPageTracesEndpoint() {
    RagTracePageRequest pageRequest = new RagTracePageRequest();
    pageRequest.setWorkspaceId(1L);
    pageRequest.setPageNum(1);
    pageRequest.setPageSize(10);

    RagTraceEntity trace =
        RagTraceEntity.create(
            "trace-01", "s1", 1L, 100L, "问题", "model", "NONE", 1, 1, 0, 1L, 2L, 0L, 10L, 13L);
    when(ragTracePersistenceService.pageTraces(pageRequest))
        .thenReturn(new PageImpl<>(List.of(trace)));

    Page<RagTraceEntity> result = controller.pageTraces(pageRequest);

    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().get(0).getTraceId()).isEqualTo("trace-01");
  }

  @Test
  @DisplayName("验证 GET /api/ai/traces/{traceId} 观测链路明细查询")
  void testGetTraceDetailEndpoint() {
    RagTraceDetailEntity detail = RagTraceDetailEntity.create("trace-01", "[]", "[]", "回答正文");
    when(ragTracePersistenceService.getDetailByTraceId("trace-01")).thenReturn(Optional.of(detail));

    RagTraceDetailEntity result = controller.getTraceDetail("trace-01");

    assertThat(result).isNotNull();
    assertThat(result.getTraceId()).isEqualTo("trace-01");
    assertThat(result.getAnswerContent()).isEqualTo("回答正文");
  }

  @Test
  @DisplayName("验证 GET /api/ai/token-stats 统计最近7天 Token 消耗正常返回")
  void testGetTokenUsageStatsSuccess() {
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime startTime = now.minusDays(7);
    io.lexiloom.application.ai.model.response.TokenUsageStatsResponse.TokenUsageSummaryVO summary =
        new io.lexiloom.application.ai.model.response.TokenUsageStatsResponse.TokenUsageSummaryVO(
            15000L, 5000L, 7000L, 2000L, 1000L, 12L);
    io.lexiloom.application.ai.model.response.TokenUsageStatsResponse.ModelTokenUsageVO modelVo =
        new io.lexiloom.application.ai.model.response.TokenUsageStatsResponse.ModelTokenUsageVO(
            "qwen-plus", 15000L, 5000L, 7000L, 2000L, 1000L, 12L, 100.0);
    io.lexiloom.application.ai.model.response.TokenUsageStatsResponse.DailyModelTokenVO
        dailyModelVo =
            new io.lexiloom.application.ai.model.response.TokenUsageStatsResponse.DailyModelTokenVO(
                "qwen-plus", 2000L, 700L, 1300L, 0L, 0L, 2L);
    io.lexiloom.application.ai.model.response.TokenUsageStatsResponse.DailyTokenUsageVO dailyVo =
        new io.lexiloom.application.ai.model.response.TokenUsageStatsResponse.DailyTokenUsageVO(
            "2026-10-21", 2000L, 700L, 1300L, 0L, 0L, 2L, List.of(dailyModelVo));
    io.lexiloom.application.ai.model.response.TokenUsageStatsResponse mockResponse =
        new io.lexiloom.application.ai.model.response.TokenUsageStatsResponse(
            7, startTime, now, 10L, summary, List.of(modelVo), List.of(dailyVo));

    when(aiStatsQueryService.getTokenUsageStatsResponse(10L, 7)).thenReturn(mockResponse);

    io.lexiloom.application.ai.model.response.TokenUsageStatsResponse response =
        controller.getTokenUsageStats(10L, 7);

    assertThat(response).isNotNull();
    assertThat(response.days()).isEqualTo(7);
    assertThat(response.total().totalTokens()).isEqualTo(15000L);
    assertThat(response.total().promptTokens()).isEqualTo(5000L);
    assertThat(response.total().completionTokens()).isEqualTo(7000L);
    assertThat(response.modelBreakdown()).hasSize(1);
    assertThat(response.modelBreakdown().get(0).modelName()).isEqualTo("qwen-plus");
    assertThat(response.modelBreakdown().get(0).percentage()).isEqualTo(100.0);
    assertThat(response.dailyStats()).hasSize(1);
    assertThat(response.dailyStats().get(0).date()).isEqualTo("2026-10-21");
    assertThat(response.dailyStats().get(0).totalTokens()).isEqualTo(2000L);
  }

  @Test
  @DisplayName("验证 GET /api/ai/token-stats 无权限访问工作空间时抛出 Forbidden 异常")
  void testGetTokenUsageStatsForbidden() {
    SecurityHolder.setAuthentication(999L);
    when(workSpaceMemberQueryService.isMember(20L, 999L)).thenReturn(false);

    assertThatThrownBy(() -> controller.getTokenUsageStats(20L, 7))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("您不是该工作空间成员");
  }

  @Test
  @DisplayName("验证 GET /api/ai/stats/tokens/api-keys 控制器正常转发查询")
  void testGetApiKeyTokenUsageStats() {
    SecurityHolder.setAuthentication(100L);
    when(workSpaceMemberQueryService.isMember(1L, 100L)).thenReturn(true);

    ApiKeyTokenUsageQueryRequest request =
        new ApiKeyTokenUsageQueryRequest("lx_live_b7c2xxxx", 1L, "DAILY", 30, null);
    ApiKeyTokenUsageResponse mockResponse =
        new ApiKeyTokenUsageResponse(
            "DAILY",
            "lx_live_b7c2****xxxx",
            1L,
            LocalDateTime.now().minusDays(30),
            LocalDateTime.now(),
            new ApiKeyTokenUsageResponse.TokenUsageSummaryVO(5000L, 2000L, 3000L, 0L, 0L, 10L),
            List.of(),
            List.of());

    when(aiStatsQueryService.getApiKeyTokenUsageStats(request)).thenReturn(mockResponse);

    ApiKeyTokenUsageResponse actual = controller.getApiKeyTokenUsageStats(request);

    assertThat(actual).isNotNull();
    assertThat(actual.periodType()).isEqualTo("DAILY");
    assertThat(actual.targetApiKey()).isEqualTo("lx_live_b7c2****xxxx");
    assertThat(actual.total().totalTokens()).isEqualTo(5000L);
    verify(aiStatsQueryService).getApiKeyTokenUsageStats(request);
  }

  @Test
  @DisplayName("验证 GET /api/ai/stats/tokens/api-keys 无权限访问空间时校验拦截")
  void testGetApiKeyTokenUsageStatsForbidden() {
    SecurityHolder.setAuthentication(999L);
    when(workSpaceMemberQueryService.isMember(2L, 999L)).thenReturn(false);

    ApiKeyTokenUsageQueryRequest request =
        new ApiKeyTokenUsageQueryRequest(null, 2L, "MONTHLY", null, 6);

    assertThatThrownBy(() -> controller.getApiKeyTokenUsageStats(request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("您不是该工作空间成员");
  }
}
