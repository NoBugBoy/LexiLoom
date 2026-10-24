package io.lexiloom.application.ai.trace;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.model.request.AiChatRequest;
import io.lexiloom.application.ai.model.response.AiChatResponse;
import io.lexiloom.application.ai.trace.model.RagTraceContext;
import io.lexiloom.application.common.LoginUserAuthentication;
import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.enums.AiCallSourceEnum;
import io.lexiloom.application.utils.DesensitizeUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * RAG 问答全生命周期链路追踪切面 (RagTraceAspect)
 *
 * <p>核心职责：
 *
 * <ul>
 *   <li>拦截标有 {@link RagTraceable} 的问答方法，生成全局唯一 traceId；
 *   <li>通过 JDK 25 的 {@link ScopedValue} 绑定 {@link RagTraceContext}，使链路下游无感获取；
 *   <li>同步问答结束后回填 traceId 并触发虚拟线程 (Virtual Thread) 异步持久化。
 * </ul>
 */
@Aspect
@Component
@Slf4j
@Order(10)
public class RagTraceAspect {

  private final RagTracePersistenceService persistenceService;
  @Nullable private final AiProperties aiProperties;

  public RagTraceAspect(RagTracePersistenceService persistenceService) {
    this(persistenceService, null);
  }

  @Autowired
  public RagTraceAspect(
      RagTracePersistenceService persistenceService, @Nullable AiProperties aiProperties) {
    this.persistenceService = persistenceService;
    this.aiProperties = aiProperties;
  }

  @Around("@annotation(ragTraceable)")
  public Object traceRagLifecycle(ProceedingJoinPoint pjp, RagTraceable ragTraceable)
      throws Throwable {
    AiChatRequest request = findChatRequest(pjp.getArgs());
    if (request == null) {
      return pjp.proceed();
    }

    String traceId = IdUtil.fastSimpleUUID();
    var loginUserOpt = SecurityHolder.getLoginUser();
    Long userId = loginUserOpt.map(LoginUserAuthentication::userId).orElse(null);
    RagTraceContext context =
        new RagTraceContext(
            traceId, request.sessionId(), request.workspaceId(), userId, request.message());

    // 链路来源与 API Key 凭证感知 (识别 Agent / OpenAPI 调用并正确绑定链路标签)
    var openApiPrincipal = SecurityHolder.getOpenApiPrincipal().orElse(null);
    String apiKey = loginUserOpt.map(LoginUserAuthentication::apiKey).orElse(null);

    if (openApiPrincipal != null || StrUtil.isNotBlank(apiKey)) {
      String sourceHeader = resolveCallSourceFromHeader();
      AiCallSourceEnum sourceEnum =
          AiCallSourceEnum.from(sourceHeader, AiCallSourceEnum.OPENAPI);
      context.recordCallSource(sourceEnum.getCode());
      if (StrUtil.isNotBlank(apiKey)) {
        context.recordApiKey(DesensitizeUtils.maskApiKey(apiKey));
      }
    } else {
      context.recordCallSource(AiCallSourceEnum.WEB_CHAT.getCode());
    }

    String effectiveModel =
        aiProperties != null
            ? aiProperties.resolveEffectiveModelName(openApiPrincipal)
            : "deepseek-chat";
    context.recordModelName(effectiveModel);

    try {
      return ScopedValue.where(RagTraceHolder.CURRENT, context)
          .call(
              () -> {
                Object result = pjp.proceed();
                if (result instanceof AiChatResponse resp) {
                  context.recordAnswer(resp.content(), effectiveModel);
                  context.recordCitations(resp.citations());
                  if (resp.promptTokens() != null || resp.completionTokens() != null) {
                    context.recordLlmTokens(
                        resp.promptTokens() != null ? resp.promptTokens().longValue() : null,
                        resp.completionTokens() != null
                            ? resp.completionTokens().longValue()
                            : null);
                  }
                  // 触发虚拟线程异步写库 (零线程池)
                  persistenceService.asyncSaveTrace(context);
                  // 组装回填 traceId 返回
                  return AiChatResponse.of(
                      resp.content(),
                      resp.thinkingContent(),
                      resp.citations(),
                      resp.latencyMs(),
                      resp.sessionId(),
                      traceId,
                      resp.promptTokens(),
                      resp.completionTokens(),
                      resp.totalTokens());
                }
                return result;
              });
    } catch (Throwable t) {
      context.markError(t);
      persistenceService.asyncSaveTrace(context);
      throw t;
    }
  }

  private AiChatRequest findChatRequest(Object[] args) {
    if (args == null) {
      return null;
    }
    for (Object arg : args) {
      if (arg instanceof AiChatRequest r) {
        return r;
      }
    }
    return null;
  }

  private String resolveCallSourceFromHeader() {
    try {
      var attrs = RequestContextHolder.getRequestAttributes();
      if (attrs instanceof ServletRequestAttributes sra) {
        HttpServletRequest req = sra.getRequest();
        return req.getHeader("X-Call-Source");
      }
    } catch (Exception ignored) {
    }
    return null;
  }
}
