package io.lexiloom.application.ai.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lexiloom.application.ai.model.request.AiChatRequest;
import io.lexiloom.application.ai.model.response.AiChatResponse;
import io.lexiloom.application.ai.trace.model.RagTraceContext;
import io.lexiloom.application.common.SecurityHolder;
import java.util.List;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class RagTraceAspectTest {

  @Mock private RagTracePersistenceService persistenceService;
  @Mock private ProceedingJoinPoint joinPoint;

  private RagTraceAspect aspect;

  @BeforeEach
  void setUp() {
    aspect = new RagTraceAspect(persistenceService);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("验证同步问答切面正常拦截、绑定 ScopedValue 上下文并触发异步落库")
  void testTraceSyncChatLifecycle() throws Throwable {
    SecurityHolder.setAuthentication(100L);
    AiChatRequest request = new AiChatRequest("测试问题", 1L, "sess-123", true);
    when(joinPoint.getArgs()).thenReturn(new Object[] {request});

    AiChatResponse expectedResponse =
        AiChatResponse.of("回复内容", null, List.of(), 50L, "sess-123", null, 100, 20, 120);
    when(joinPoint.proceed())
        .thenAnswer(
            invocation -> {
              // 验证在 proceed() 期间当前线程处于 ScopedValue 绑定状态
              assertThat(RagTraceHolder.get()).isNotNull();
              assertThat(RagTraceHolder.get().getQuery()).isEqualTo("测试问题");
              assertThat(RagTraceHolder.get().getWorkspaceId()).isEqualTo(1L);
              return expectedResponse;
            });

    RagTraceable annotation = mock(RagTraceable.class);
    Object result = aspect.traceRagLifecycle(joinPoint, annotation);

    assertThat(result).isInstanceOf(AiChatResponse.class);
    AiChatResponse chatResp = (AiChatResponse) result;
    assertThat(chatResp.content()).isEqualTo("回复内容");
    assertThat(chatResp.traceId()).isNotBlank();
    assertThat(chatResp.promptTokens()).isEqualTo(100);
    assertThat(chatResp.completionTokens()).isEqualTo(20);
    assertThat(chatResp.totalTokens()).isEqualTo(120);
    verify(persistenceService).asyncSaveTrace(any());
  }

  @Test
  @DisplayName("验证流式问答切面由回调驱动持久化，切面同步退出时不抢先持久化")
  void testTraceStreamChatLifecycleDoesNotEagerPersist() throws Throwable {
    SecurityHolder.setAuthentication(100L);
    AiChatRequest request = new AiChatRequest("流式测试", 1L, "sess-stream", false);
    when(joinPoint.getArgs()).thenReturn(new Object[] {request});

    when(joinPoint.proceed())
        .thenAnswer(
            invocation -> {
              assertThat(RagTraceHolder.get()).isNotNull();
              return null;
            });

    RagTraceable annotation = mock(RagTraceable.class);
    Object result = aspect.traceRagLifecycle(joinPoint, annotation);

    assertThat(result).isNull();
    // streamChat persist 由 DefaultRagChatService onComplete/onError 中的 virtual thread 执行，切面本身不重复持久化
    verify(persistenceService, org.mockito.Mockito.never()).asyncSaveTrace(any());
  }

  @Test
  @DisplayName("验证来自 OpenAPI 凭据的请求自动标记 callSource 为 OPENAPI 并记录脱敏 API Key")
  void testTraceOpenApiAgentLifecycle() throws Throwable {
    io.lexiloom.application.common.security.OpenApiPrincipal principal =
        new io.lexiloom.application.common.security.OpenApiPrincipal(
            1L, 100L, "AgentKey", null, null, "DEEPSEEK", "sk-123");
    SecurityHolder.setAuthentication(100L, List.of(), "lx_live_secret_key_123", principal);

    AiChatRequest request = new AiChatRequest("Agent 问题", 1L, "sess-agent", false);
    when(joinPoint.getArgs()).thenReturn(new Object[] {request});

    AiChatResponse expectedResponse =
        AiChatResponse.of("回复内容", null, List.of(), 50L, "sess-agent", null, 100, 20, 120);
    when(joinPoint.proceed())
        .thenAnswer(
            invocation -> {
              RagTraceContext ctx = RagTraceHolder.get();
              assertThat(ctx).isNotNull();
              assertThat(ctx.getCallSource()).isEqualTo("OPENAPI");
              assertThat(ctx.getApiKey()).isEqualTo("lx_live_secr****_123");
              return expectedResponse;
            });

    RagTraceable annotation = mock(RagTraceable.class);
    aspect.traceRagLifecycle(joinPoint, annotation);
    verify(persistenceService).asyncSaveTrace(any());
  }

  @Test
  @DisplayName("验证来自 OpenAPI 且配置 BYOK 的请求，链路记录平台枚举模型名称")
  void testTraceOpenApiWithByokModelNameResolution() throws Throwable {
    io.lexiloom.application.ai.config.AiProperties aiProps =
        new io.lexiloom.application.ai.config.AiProperties();
    aiProps.setChatModel("deepseek-chat");
    RagTraceAspect customAspect = new RagTraceAspect(persistenceService, aiProps);

    io.lexiloom.application.common.security.OpenApiPrincipal principal =
        new io.lexiloom.application.common.security.OpenApiPrincipal(
            1L, 100L, "QwenKey", null, null, "QWEN", "sk-qwen-key");
    SecurityHolder.setAuthentication(100L, List.of(), "lx_live_qwen_key_123", principal);

    AiChatRequest request = new AiChatRequest("Qwen 问题", 1L, "sess-qwen", false);
    when(joinPoint.getArgs()).thenReturn(new Object[] {request});

    AiChatResponse expectedResponse =
        AiChatResponse.of("Qwen 回复", null, List.of(), 50L, "sess-qwen", null, 100, 20, 120);
    when(joinPoint.proceed())
        .thenAnswer(
            invocation -> {
              RagTraceContext ctx = RagTraceHolder.get();
              assertThat(ctx).isNotNull();
              assertThat(ctx.getModelName()).isEqualTo("QWEN");
              return expectedResponse;
            });

    RagTraceable annotation = mock(RagTraceable.class);
    customAspect.traceRagLifecycle(joinPoint, annotation);
  }
}
