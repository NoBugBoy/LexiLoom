package io.lexiloom.application.ai.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lexiloom.application.ai.api.AiStatsQueryApi;
import io.lexiloom.application.ai.model.request.AiChatRequest;
import io.lexiloom.application.ai.model.request.OpenApiChatCompletionRequest;
import io.lexiloom.application.ai.model.response.AiChatResponse;
import io.lexiloom.application.ai.rag.KnowledgeRetrievalQueryService;
import io.lexiloom.application.ai.rag.RagChatService;
import io.lexiloom.application.ai.service.AiAccessSecurityValidator;
import io.lexiloom.application.ai.service.OpenApiResultAssembler;
import io.lexiloom.application.ai.trace.RagTracePersistenceService;
import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.common.security.OpenApiPrincipal;
import io.lexiloom.application.document.api.DocumentQueryApi;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.api.WorkSpaceMemberQueryService;
import io.lexiloom.application.user.api.WorkSpaceQueryService;
import io.lexiloom.application.user.api.dto.WorkSpaceDTO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ExtendWith(MockitoExtension.class)
class OpenApiQueryServiceChatTest {

  @Mock private KnowledgeRetrievalQueryService knowledgeRetrievalQueryService;
  @Mock private WorkSpaceQueryService workSpaceQueryService;
  @Mock private WorkSpaceMemberQueryService workSpaceMemberQueryService;
  @Mock private DocumentQueryApi documentQueryApi;
  @Mock private AiStatsQueryApi aiStatsQueryApi;
  @Mock private RagTracePersistenceService ragTracePersistenceService;
  @Mock private RagChatService ragChatService;

  private OpenApiQueryServiceImpl service;

  @BeforeEach
  void setUp() {
    service =
        new OpenApiQueryServiceImpl(
            knowledgeRetrievalQueryService,
            workSpaceQueryService,
            documentQueryApi,
            new AiAccessSecurityValidator(workSpaceMemberQueryService),
            new OpenApiResultAssembler(),
            aiStatsQueryApi,
            ragTracePersistenceService,
            ragChatService);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("chat - Scope 未包含 chat:completion 时拦截并抛出 403")
  void testChat_scopeForbidden() {
    OpenApiPrincipal principal =
        new OpenApiPrincipal(1L, 10L, "Read-Only-Key", "kb:read,kb:search", null, "NONE", null);
    SecurityHolder.setAuthentication(10L, List.of(), "lx_live_123", principal);

    OpenApiChatCompletionRequest req =
        new OpenApiChatCompletionRequest("你好", 1L, null, false, false, null, null);

    assertThatThrownBy(() -> service.chat(req))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("未授予 chat:completion 权限范围");
  }

  @Test
  @DisplayName("chat - 请求空间未在 API Key 白名单内抛出 403")
  void testChat_workspaceNotAllowed() {
    OpenApiPrincipal principal =
        new OpenApiPrincipal(1L, 10L, "Scoped-Key", "chat:completion", "2,3", "NONE", null);
    SecurityHolder.setAuthentication(10L, List.of(), "lx_live_123", principal);

    OpenApiChatCompletionRequest req =
        new OpenApiChatCompletionRequest("你好", 1L, null, false, false, null, null);

    assertThatThrownBy(() -> service.chat(req))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("未被允许访问工作空间: 1");
  }

  @Test
  @DisplayName("chat - 校验通过并正常返回大模型问答响应")
  void testChat_success() {
    OpenApiPrincipal principal =
        new OpenApiPrincipal(1L, 10L, "BYOK-Key", "chat:completion", "1", "QWEN", "sk-byok-qwen");
    SecurityHolder.setAuthentication(10L, List.of(), "lx_live_123", principal);
    when(workSpaceMemberQueryService.isMember(1L, 10L)).thenReturn(true);

    AiChatResponse mockResp =
        new AiChatResponse("Qwen 模型回复", null, List.of(), 120L, "trace-abc");
    when(ragChatService.chat(any(AiChatRequest.class))).thenReturn(mockResp);

    OpenApiChatCompletionRequest req =
        new OpenApiChatCompletionRequest("你好", 1L, null, false, false, null, null);

    AiChatResponse resp = service.chat(req);
    assertThat(resp).isNotNull();
    assertThat(resp.content()).isEqualTo("Qwen 模型回复");
  }

  @Test
  @DisplayName("streamChat - 校验通过并成功创建 SseEmitter")
  void testStreamChat_success() {
    OpenApiPrincipal principal =
        new OpenApiPrincipal(1L, 10L, "BYOK-Key", "chat:completion", null, "DEEPSEEK", "sk-byok-deepseek");
    SecurityHolder.setAuthentication(10L, List.of(), "lx_live_123", principal);
    when(workSpaceMemberQueryService.isMember(1L, 10L)).thenReturn(true);

    OpenApiChatCompletionRequest req =
        new OpenApiChatCompletionRequest("你好流式", 1L, null, true, false, null, null);

    SseEmitter emitter = service.streamChat(req);
    assertThat(emitter).isNotNull();
    verify(ragChatService).streamChat(any(AiChatRequest.class), any(SseEmitter.class));
  }
}
