package io.lexiloom.application.ai.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lexiloom.application.ai.api.AiStatsQueryApi;
import io.lexiloom.application.ai.gateway.rerank.ScoredDocChunkVO;
import io.lexiloom.application.ai.model.search.DocChunkDocument;
import io.lexiloom.application.ai.model.vo.McpDocumentDetailVO;
import io.lexiloom.application.ai.model.vo.McpSearchResultVO;
import io.lexiloom.application.ai.model.vo.McpWorkspaceVO;
import io.lexiloom.application.ai.rag.KnowledgeRetrievalQueryService;
import io.lexiloom.application.ai.service.AiAccessSecurityValidator;
import io.lexiloom.application.ai.service.OpenApiResultAssembler;
import io.lexiloom.application.ai.trace.RagTracePersistenceService;
import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.document.api.DocumentQueryApi;
import io.lexiloom.application.document.api.dto.DocumentDTO;
import io.lexiloom.application.document.api.dto.DocumentStateEnum;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
import io.lexiloom.application.enums.WorkSpaceRoleEnum;
import io.lexiloom.application.enums.WorkSpaceStatusEnum;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.api.WorkSpaceMemberQueryService;
import io.lexiloom.application.user.api.WorkSpaceQueryService;
import io.lexiloom.application.user.api.dto.WorkSpaceDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class OpenApiQueryServiceRetrievalTest {

  @Mock private KnowledgeRetrievalQueryService knowledgeRetrievalQueryService;
  @Mock private WorkSpaceQueryService workSpaceQueryService;
  @Mock private WorkSpaceMemberQueryService workSpaceMemberQueryService;
  @Mock private DocumentQueryApi documentQueryApi;
  @Mock private AiStatsQueryApi aiStatsQueryApi;
  @Mock private RagTracePersistenceService ragTracePersistenceService;

  private OpenApiQueryServiceImpl openApiQueryService;

  @BeforeEach
  void setUp() {
    openApiQueryService =
        new OpenApiQueryServiceImpl(
            knowledgeRetrievalQueryService,
            workSpaceQueryService,
            documentQueryApi,
            new AiAccessSecurityValidator(workSpaceMemberQueryService),
            new OpenApiResultAssembler(),
            aiStatsQueryApi,
            ragTracePersistenceService,
            null);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("验证管理员获取可用工作空间列表与统计指标")
  void testListWorkspacesAsAdmin() {
    SecurityHolder.setAuthentication(1L, List.of("ADMIN"));

    WorkSpaceDTO ws =
        new WorkSpaceDTO(
            101L,
            "核心空间",
            "测试简介",
            "ws-core",
            null,
            1L,
            50,
            WorkSpaceStatusEnum.NORMAL,
            WorkSpaceAclEnum.PUBLIC,
            null,
            false,
            LocalDateTime.now());

    when(workSpaceQueryService.listAllWorkspaces()).thenReturn(List.of(ws));
    when(documentQueryApi.countDocumentsByWorkspaceId(101L)).thenReturn(42L);
    when(aiStatsQueryApi.countChunksByWorkspaceId(101L)).thenReturn(1560L);

    List<McpWorkspaceVO> result = openApiQueryService.listWorkspaces(false);

    assertThat(result).hasSize(1);
    McpWorkspaceVO vo = result.getFirst();
    assertThat(vo.id()).isEqualTo(101L);
    assertThat(vo.name()).isEqualTo("核心空间");
    assertThat(vo.description()).isEqualTo("测试简介");
    assertThat(vo.totalDocuments()).isEqualTo(42L);
    assertThat(vo.totalChunks()).isEqualTo(1560L);
  }

  @Test
  @DisplayName("验证普通用户获取自身所在空间列表")
  void testListWorkspacesAsNormalUser() {
    SecurityHolder.setAuthentication(200L, List.of());

    WorkSpaceDTO ws =
        new WorkSpaceDTO(
            50L,
            "普通空间",
            "测试",
            "ws-norm",
            null,
            200L,
            50,
            WorkSpaceStatusEnum.NORMAL,
            WorkSpaceAclEnum.INTERNAL,
            null,
            false,
            LocalDateTime.now());

    when(workSpaceQueryService.listMyWorkspaceDTOs(200L)).thenReturn(List.of(ws));
    when(documentQueryApi.countDocumentsByWorkspaceId(50L)).thenReturn(10L);
    when(aiStatsQueryApi.countChunksByWorkspaceId(50L)).thenReturn(200L);

    List<McpWorkspaceVO> result = openApiQueryService.listWorkspaces(false);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().id()).isEqualTo(50L);
    assertThat(result.getFirst().totalDocuments()).isEqualTo(10L);
  }

  @Test
  @DisplayName("验证检索入参防御拦截：Query 为空抛出 400")
  void testSearchValidationBlankQuery() {
    assertThatThrownBy(() -> openApiQueryService.search("", 1L, 5, 0.5, "OPENAPI", false))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("检索内容 q 不能为空");

    assertThatThrownBy(() -> openApiQueryService.search("   ", 1L, 5, 0.5, "OPENAPI", false))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("检索内容 q 不能为空");
  }

  @Test
  @DisplayName("验证非空间成员尝试检索抛出 403")
  void testSearchForbiddenForNonMember() {
    SecurityHolder.setAuthentication(200L, List.of());
    when(workSpaceMemberQueryService.isMember(1L, 200L)).thenReturn(false);

    assertThatThrownBy(() -> openApiQueryService.search("问答", 1L, 5, 0.5, "OPENAPI", false))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("您不是该工作空间成员");
  }

  @Test
  @DisplayName("验证纯语义检索成功返回切片列表与坐标格式化")
  void testSearchSuccessfully() {
    SecurityHolder.setAuthentication(1L, List.of("ADMIN"));

    DocChunkDocument chunk =
        DocChunkDocument.builder()
            .id("88_2")
            .chunkId("chunk_0192a8e1")
            .documentId("88")
            .workspaceId("1")
            .title("RAG 链路观测与成本治理方案")
            .categoryId("10")
            .content("统计指标必须覆盖全周期总计...")
            .chunkIndex(2)
            .page(1)
            .bbox(List.of(100f, 200f, 500f, 350f))
            .build();

    when(knowledgeRetrievalQueryService.retrieveScoredChunks(1L, "Token 统计", 5, 0.50))
        .thenReturn(List.of(ScoredDocChunkVO.of(chunk, 0.935)));

    when(documentQueryApi.getCategoryNames(Set.of(10L))).thenReturn(Map.of(10L, "技术规范"));

    McpSearchResultVO result =
        openApiQueryService.search("Token 统计", 1L, 5, 0.50, "OPENAPI", false);

    assertThat(result.query()).isEqualTo("Token 统计");
    assertThat(result.workspaceId()).isEqualTo(1L);
    assertThat(result.totalMatches()).isEqualTo(1);
    assertThat(result.results()).hasSize(1);

    var item = result.results().getFirst();
    assertThat(item.chunkId()).isEqualTo("chunk_0192a8e1");
    assertThat(item.documentId()).isEqualTo(88L);
    assertThat(item.documentTitle()).isEqualTo("RAG 链路观测与成本治理方案");
    assertThat(item.categoryName()).isEqualTo("技术规范");
    assertThat(item.score()).isEqualTo(0.935);
    assertThat(item.chunkIndex()).isEqualTo(2);
    assertThat(item.pageNumber()).isEqualTo(1);
    assertThat(item.deepDocCoordinates()).isEqualTo("[{\"x\":100,\"y\":200,\"w\":400,\"h\":150}]");
  }

  @Test
  @DisplayName("验证获取文档正文详情：文档不存在抛出 404")
  void testGetDocumentDetailNotFound() {
    when(documentQueryApi.findDocumentById(999L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> openApiQueryService.getDocumentDetail(999L, false))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("文档不存在: 999");
  }

  @Test
  @DisplayName("验证获取文档正文详情：私密文档无权访问抛出 403")
  void testGetDocumentDetailForbidden() {
    SecurityHolder.setAuthentication(200L, List.of());

    DocumentDTO doc =
        new DocumentDTO(
            88L,
            1L,
            5L,
            "私密设计",
            "摘要",
            "# 正文内容",
            DocumentStateEnum.APPROVED,
            null,
            WorkSpaceAclEnum.PRIVATE,
            null,
            null,
            1,
            "v1.0",
            null,
            100,
            0,
            999L,
            LocalDateTime.now(),
            LocalDateTime.now());

    when(documentQueryApi.findDocumentById(88L)).thenReturn(Optional.of(doc));
    when(workSpaceMemberQueryService.hasRole(1L, 200L, WorkSpaceRoleEnum.ADMIN)).thenReturn(false);

    assertThatThrownBy(() -> openApiQueryService.getDocumentDetail(88L, false))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("您无权访问该私密文档");
  }

  @Test
  @DisplayName("验证获取文档正文详情成功返回 Markdown 内容与元数据")
  void testGetDocumentDetailSuccessfully() {
    SecurityHolder.setAuthentication(1L, List.of("ADMIN"));

    LocalDateTime now = LocalDateTime.now();
    DocumentDTO doc =
        new DocumentDTO(
            88L,
            1L,
            5L,
            "RAG 观测与成本治理方案",
            "摘要",
            "# 正文 Markdown 全文",
            DocumentStateEnum.APPROVED,
            null,
            WorkSpaceAclEnum.PUBLIC,
            null,
            null,
            2,
            "v2.0",
            null,
            200,
            10,
            1L,
            now,
            now);

    when(documentQueryApi.findDocumentById(88L)).thenReturn(Optional.of(doc));
    when(documentQueryApi.getCategoryNames(List.of(5L))).thenReturn(Map.of(5L, "技术规范"));

    McpDocumentDetailVO detail = openApiQueryService.getDocumentDetail(88L, false);

    assertThat(detail.id()).isEqualTo(88L);
    assertThat(detail.title()).isEqualTo("RAG 观测与成本治理方案");
    assertThat(detail.workspaceId()).isEqualTo(1L);
    assertThat(detail.categoryName()).isEqualTo("技术规范");
    assertThat(detail.status()).isEqualTo("APPROVED");
    assertThat(detail.version()).isEqualTo(2);
    assertThat(detail.content()).isEqualTo("# 正文 Markdown 全文");
    assertThat(detail.createTime()).isEqualTo(now);
    assertThat(detail.updateTime()).isEqualTo(now);
  }

  @Test
  @DisplayName("验证纯切片检索触发全链路可观测性记录，并正确记录 API Key 与默认缺失指标")
  void testSearchRecordsObservabilityWithApiKeyAndDefaults() {
    SecurityHolder.setAuthentication(1L, List.of("ADMIN"), "lx_live_agent_key_abc");

    DocChunkDocument chunk =
        DocChunkDocument.builder()
            .id("chunk-1")
            .documentId("10")
            .title("LexiLoom 知识库白皮书")
            .categoryId("2")
            .content("LexiLoom 提供无头检索能力")
            .chunkIndex(0)
            .page(1)
            .build();
    ScoredDocChunkVO scored = ScoredDocChunkVO.of(chunk, 0.95, 1, "RERANK");

    when(knowledgeRetrievalQueryService.retrieveScoredChunks(eq(1L), eq("白皮书"), eq(10), eq(0.50)))
        .thenReturn(List.of(scored));
    when(documentQueryApi.getCategoryNames(Set.of(2L))).thenReturn(Map.of(2L, "白皮书类"));

    org.mockito.ArgumentCaptor<io.lexiloom.application.ai.trace.model.RagTraceContext> captor =
        org.mockito.ArgumentCaptor.forClass(io.lexiloom.application.ai.trace.model.RagTraceContext.class);

    McpSearchResultVO result =
        openApiQueryService.search("白皮书", 1L, 10, 0.50, "CLI_SKILL", false);

    assertThat(result).isNotNull();
    assertThat(result.traceId()).isNotBlank();
    assertThat(result.results()).hasSize(1);

    verify(ragTracePersistenceService).asyncSaveTrace(captor.capture());
    io.lexiloom.application.ai.trace.model.RagTraceContext context = captor.getValue();
    assertThat(context.getTraceId()).isEqualTo(result.traceId());
    assertThat(context.getCallSource()).isEqualTo("CLI_SKILL");
    assertThat(context.getApiKey()).isEqualTo("lx_live_agen****_abc");
    assertThat(context.getModelName()).isEqualTo("deepseek-chat");
    assertThat(context.getAnswerContent()).isEqualTo("[CLI Skill Headless Retrieval: No LLM Generation]");
    assertThat(context.getPromptTokens()).isEqualTo(0);
    assertThat(context.getCompletionTokens()).isEqualTo(0);
    assertThat(context.getGenerationMs().get()).isEqualTo(0L);
    assertThat(context.getCitations()).hasSize(1);
    assertThat(context.getCitations().get(0).docTitle()).isEqualTo("LexiLoom 知识库白皮书");
  }
}
