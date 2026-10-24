package io.lexiloom.application.ai.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.querydsl.core.types.Predicate;
import io.lexiloom.application.ai.model.vo.CitationVO;
import io.lexiloom.application.ai.trace.entity.RagTraceDetailEntity;
import io.lexiloom.application.ai.trace.entity.RagTraceEntity;
import io.lexiloom.application.ai.trace.model.CandidateTraceItem;
import io.lexiloom.application.ai.trace.model.FilterDecision;
import io.lexiloom.application.ai.trace.model.FilterReason;
import io.lexiloom.application.ai.trace.model.RagFeedbackRequest;
import io.lexiloom.application.ai.trace.model.RagTraceContext;
import io.lexiloom.application.ai.trace.model.RagTracePageRequest;
import io.lexiloom.application.ai.trace.repository.RagTraceDetailRepository;
import io.lexiloom.application.ai.trace.repository.RagTraceRepository;
import io.lexiloom.application.exception.BusinessException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class RagTracePersistenceServiceTest {

  @Mock private RagTraceRepository ragTraceRepository;
  @Mock private RagTraceDetailRepository ragTraceDetailRepository;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private RagTracePersistenceService service;

  @BeforeEach
  void setUp() {
    service =
        new RagTracePersistenceService(ragTraceRepository, ragTraceDetailRepository, objectMapper);
  }

  @Test
  @DisplayName("验证 saveTrace 正常写入主表与不可变明细表")
  void testSaveTraceSuccess() {
    RagTraceContext context = new RagTraceContext("trace-001", "sess-001", 100L, 200L, "什么是 CQRS？");
    context.recordEmbeddingTime(15L);
    context.recordRetrievalTime(25L);
    context.recordRerankTime(35L);
    context.recordGenerationTime(500L);
    context.recordRerankStrategy("CROSS_ENCODER");
    context.recordAnswer("CQRS 是读写职责分离架构", "deepseek-chat");
    context.recordEmbeddingTokens(18L);
    context.recordRerankTokens(120L);
    context.recordLlmTokens(1500L, 200L);

    context.recordCandidate(
        new CandidateTraceItem(
            "chunk-1",
            "doc-1",
            "CQRS 说明",
            "KNN",
            0.92f,
            0.92,
            0.92,
            0.95,
            1,
            FilterDecision.ACCEPTED,
            FilterReason.NONE));
    context.recordCandidate(
        new CandidateTraceItem(
            "chunk-2",
            "doc-1",
            "CQRS 说明",
            "BM25",
            0.50f,
            0.50,
            0.20,
            0.10,
            2,
            FilterDecision.REJECTED,
            FilterReason.BELOW_SIMILARITY_THRESHOLD));

    CitationVO citation =
        new CitationVO(
            1,
            1L,
            "CQRS 说明",
            "第一章",
            "CQRS 是读写职责分离",
            0.95,
            1,
            List.of(10f, 20f, 100f, 200f),
            "text",
            "anchor-1",
            "KNN");
    context.recordCitations(List.of(citation));

    when(ragTraceRepository.exists(any(Predicate.class))).thenReturn(false);

    service.saveTrace(context);

    ArgumentCaptor<RagTraceEntity> traceCaptor = ArgumentCaptor.forClass(RagTraceEntity.class);
    verify(ragTraceRepository).save(traceCaptor.capture());
    RagTraceEntity savedTrace = traceCaptor.getValue();
    assertThat(savedTrace.getTraceId()).isEqualTo("trace-001");
    assertThat(savedTrace.getSessionId()).isEqualTo("sess-001");
    assertThat(savedTrace.getWorkspaceId()).isEqualTo(100L);
    assertThat(savedTrace.getUserId()).isEqualTo(200L);
    assertThat(savedTrace.getQuery()).isEqualTo("什么是 CQRS？");
    assertThat(savedTrace.getModelName()).isEqualTo("deepseek-chat");
    assertThat(savedTrace.getRerankStrategy()).isEqualTo("CROSS_ENCODER");
    assertThat(savedTrace.getTotalCandidates()).isEqualTo(2);
    assertThat(savedTrace.getRetainedChunks()).isEqualTo(1);
    assertThat(savedTrace.getFilteredChunks()).isEqualTo(1);
    assertThat(savedTrace.getEmbeddingTimeMs()).isEqualTo(15L);
    assertThat(savedTrace.getRetrievalTimeMs()).isEqualTo(25L);
    assertThat(savedTrace.getRerankTimeMs()).isEqualTo(35L);
    assertThat(savedTrace.getGenerationTimeMs()).isEqualTo(500L);
    assertThat(savedTrace.getEmbeddingTokens()).isEqualTo(18);
    assertThat(savedTrace.getRerankTokens()).isEqualTo(120);
    assertThat(savedTrace.getPromptTokens()).isEqualTo(1500);
    assertThat(savedTrace.getCompletionTokens()).isEqualTo(200);
    assertThat(savedTrace.getTotalTokens()).isEqualTo(1838);

    ArgumentCaptor<RagTraceDetailEntity> detailCaptor =
        ArgumentCaptor.forClass(RagTraceDetailEntity.class);
    verify(ragTraceDetailRepository).save(detailCaptor.capture());
    RagTraceDetailEntity savedDetail = detailCaptor.getValue();
    assertThat(savedDetail.getTraceId()).isEqualTo("trace-001");
    assertThat(savedDetail.getCandidateSnapshotJson()).contains("chunk-1").contains("chunk-2");
    assertThat(savedDetail.getCitationsJson()).contains("CQRS 是读写职责分离");
    assertThat(savedDetail.getAnswerContent()).isEqualTo("CQRS 是读写职责分离架构");
  }

  @Test
  @DisplayName("验证 saveTrace 幂等性：已存在的 traceId 不重复写入")
  void testSaveTraceIdempotent() {
    RagTraceContext context = new RagTraceContext("trace-dup", "sess-1", 1L, 1L, "测试");
    when(ragTraceRepository.exists(any(Predicate.class))).thenReturn(true);

    service.saveTrace(context);

    verify(ragTraceRepository, never()).save(any());
    verify(ragTraceDetailRepository, never()).save(any());
  }

  @Test
  @DisplayName("验证 recordFeedback 正常更新主表评价与标签")
  void testRecordFeedbackSuccess() {
    RagTraceEntity entity =
        RagTraceEntity.create(
            "trace-fb", "sess-1", 1L, 2L, "提问", "model", "NONE", 1, 1, 0, 10L, 20L, 0L, 100L, 130L);
    when(ragTraceRepository.findOne(any(Predicate.class))).thenReturn(Optional.of(entity));

    RagFeedbackRequest request =
        new RagFeedbackRequest(1, List.of("ACCURATE", "HELPFUL"), "解答非常准确！");
    service.recordFeedback("trace-fb", request, 2L);

    verify(ragTraceRepository).save(entity);
    assertThat(entity.getFeedbackRating()).isEqualTo(1);
    assertThat(entity.getFeedbackTags()).isEqualTo("ACCURATE,HELPFUL");
    assertThat(entity.getFeedbackComment()).isEqualTo("解答非常准确！");
  }

  @Test
  @DisplayName("验证 recordFeedback 记录不存在时抛出 BusinessException")
  void testRecordFeedbackNotFound() {
    when(ragTraceRepository.findOne(any(Predicate.class))).thenReturn(Optional.empty());

    RagFeedbackRequest request = new RagFeedbackRequest(-1, List.of("HALLUCINATION"), "有幻觉");
    assertThatThrownBy(() -> service.recordFeedback("trace-none", request, 2L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("未找到对应的链路记录");
  }

  @Test
  @DisplayName("验证 QueryDSL 分页查询 pageTraces")
  void testPageTraces() {
    RagTracePageRequest request = new RagTracePageRequest();
    request.setWorkspaceId(1L);
    request.setFeedbackRating(1);
    request.setPageNum(1);
    request.setPageSize(10);

    RagTraceEntity entity =
        RagTraceEntity.create(
            "trace-p1", "s1", 1L, 2L, "问题", "model", "NONE", 1, 1, 0, 1L, 2L, 0L, 10L, 13L);
    when(ragTraceRepository.findAll(any(Predicate.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(entity)));

    Page<RagTraceEntity> page = service.pageTraces(request);

    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getContent().get(0).getTraceId()).isEqualTo("trace-p1");
  }

  @Test
  @DisplayName("验证 pageTraces 传入 CLI_SKILL / AGENT_MCP 时构建包含多来源的兼容条件")
  void testPageTracesWithAgentMcpSource() {
    RagTracePageRequest request = new RagTracePageRequest();
    request.setCallSource("CLI_SKILL");

    RagTraceEntity entity =
        RagTraceEntity.create(
            "trace-agent", "s1", 1L, 2L, "问题", "model", "NONE", 1, 1, 0, 1L, 2L, 0L, 10L, 13L);
    when(ragTraceRepository.findAll(any(Predicate.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(entity)));

    Page<RagTraceEntity> page = service.pageTraces(request);
    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getContent().getFirst().getTraceId()).isEqualTo("trace-agent");
  }

  @Test
  @DisplayName("验证 QueryDSL 根据 traceId 获取明细 getDetailByTraceId")
  void testGetDetailByTraceId() {
    RagTraceDetailEntity detail = RagTraceDetailEntity.create("trace-d1", "[]", "[]", "回答内容");
    when(ragTraceDetailRepository.findOne(any(Predicate.class))).thenReturn(Optional.of(detail));

    Optional<RagTraceDetailEntity> result = service.getDetailByTraceId("trace-d1");

    assertThat(result).isPresent();
    assertThat(result.get().getTraceId()).isEqualTo("trace-d1");
    assertThat(result.get().getAnswerContent()).isEqualTo("回答内容");
  }

  @Test
  @DisplayName("验证 saveTrace 保存 CLI_SKILL 来源记录时正确持久化 callSource、apiKey 与缺失指标默认值")
  void testSaveTraceAgentModeWithApiKeyAndDefaults() {
    RagTraceContext context = new RagTraceContext("trace-agent-001", null, 10L, 1L, "检索系统架构");
    context.recordCallSource("CLI_SKILL");
    context.recordApiKey("lx_live_agent_key_xyz");
    context.recordAnswer("[CLI Skill Headless Retrieval: No LLM Generation]", "deepseek-chat");
    context.recordRerankStrategy("CROSS_ENCODER");
    context.recordEmbeddingTime(20L);
    context.recordRetrievalTime(15L);
    context.recordRerankTime(45L);
    context.recordRerankTokens(160L);
    // 未调用 LLM，generationTime 与 prompt/completion tokens 保持默认 0

    when(ragTraceRepository.exists(any(Predicate.class))).thenReturn(false);

    service.saveTrace(context);

    ArgumentCaptor<RagTraceEntity> traceCaptor = ArgumentCaptor.forClass(RagTraceEntity.class);
    verify(ragTraceRepository).save(traceCaptor.capture());
    RagTraceEntity saved = traceCaptor.getValue();
    assertThat(saved.getTraceId()).isEqualTo("trace-agent-001");
    assertThat(saved.getSessionId()).isNull();
    assertThat(saved.getCallSource()).isEqualTo("CLI_SKILL");
    assertThat(saved.getApiKey()).isEqualTo("lx_live_agen****_xyz");
    assertThat(saved.getModelName()).isEqualTo("deepseek-chat");
    assertThat(saved.getPromptTokens()).isEqualTo(0);
    assertThat(saved.getCompletionTokens()).isEqualTo(0);
    assertThat(saved.getGenerationTimeMs()).isEqualTo(0L);
    assertThat(saved.getRerankTokens()).isEqualTo(160);
    assertThat(saved.getTotalTokens()).isEqualTo(160);

    ArgumentCaptor<RagTraceDetailEntity> detailCaptor =
        ArgumentCaptor.forClass(RagTraceDetailEntity.class);
    verify(ragTraceDetailRepository).save(detailCaptor.capture());
    RagTraceDetailEntity detail = detailCaptor.getValue();
    assertThat(detail.getTraceId()).isEqualTo("trace-agent-001");
    assertThat(detail.getAnswerContent()).isEqualTo("[CLI Skill Headless Retrieval: No LLM Generation]");
  }
}
