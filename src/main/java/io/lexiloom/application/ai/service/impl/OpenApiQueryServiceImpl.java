package io.lexiloom.application.ai.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.api.AiStatsQueryApi;
import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.gateway.rerank.ScoredDocChunkVO;
import io.lexiloom.application.ai.model.request.AiChatRequest;
import io.lexiloom.application.ai.model.request.OpenApiChatCompletionRequest;
import io.lexiloom.application.ai.model.response.AiChatResponse;
import io.lexiloom.application.ai.model.search.DocChunkDocument;
import io.lexiloom.application.ai.model.vo.CitationVO;
import io.lexiloom.application.ai.model.vo.McpChunkResultVO;
import io.lexiloom.application.ai.model.vo.McpDocumentDetailVO;
import io.lexiloom.application.ai.model.vo.McpSearchResultVO;
import io.lexiloom.application.ai.model.vo.McpWorkspaceVO;
import io.lexiloom.application.ai.rag.KnowledgeRetrievalQueryService;
import io.lexiloom.application.ai.rag.RagChatService;
import io.lexiloom.application.ai.service.AiAccessSecurityValidator;
import io.lexiloom.application.ai.service.OpenApiQueryService;
import io.lexiloom.application.ai.service.OpenApiResultAssembler;
import io.lexiloom.application.ai.trace.RagTraceHolder;
import io.lexiloom.application.ai.trace.RagTracePersistenceService;
import io.lexiloom.application.ai.trace.model.RagTraceContext;
import io.lexiloom.application.common.LoginUserAuthentication;
import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.common.security.OpenApiPrincipal;
import io.lexiloom.application.document.api.DocumentQueryApi;
import io.lexiloom.application.document.api.dto.DocumentDTO;
import io.lexiloom.application.enums.AiCallSourceEnum;
import io.lexiloom.application.enums.OpenApiScopeEnum;
import io.lexiloom.application.exception.BusinessException;
import io.lexiloom.application.user.api.WorkSpaceQueryService;
import io.lexiloom.application.user.api.dto.WorkSpaceDTO;
import io.lexiloom.application.utils.DesensitizeUtils;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 开放平台 OpenAPI 业务查询编排实现
 */
@Slf4j
@Service
public class OpenApiQueryServiceImpl implements OpenApiQueryService {

  private static final long SSE_TIMEOUT_MS = 180_000L;

  private final KnowledgeRetrievalQueryService knowledgeRetrievalQueryService;
  private final WorkSpaceQueryService workSpaceQueryService;
  private final DocumentQueryApi documentQueryApi;
  private final AiAccessSecurityValidator accessSecurityValidator;
  private final OpenApiResultAssembler resultAssembler;
  @Nullable private final AiStatsQueryApi aiStatsQueryApi;
  @Nullable private final RagTracePersistenceService ragTracePersistenceService;
  @Nullable private final RagChatService ragChatService;
  @Nullable private final AiProperties aiProperties;

  public OpenApiQueryServiceImpl(
      KnowledgeRetrievalQueryService knowledgeRetrievalQueryService,
      WorkSpaceQueryService workSpaceQueryService,
      DocumentQueryApi documentQueryApi,
      AiAccessSecurityValidator accessSecurityValidator,
      OpenApiResultAssembler resultAssembler,
      @Nullable AiStatsQueryApi aiStatsQueryApi,
      @Nullable RagTracePersistenceService ragTracePersistenceService,
      @Nullable RagChatService ragChatService) {
    this(
        knowledgeRetrievalQueryService,
        workSpaceQueryService,
        documentQueryApi,
        accessSecurityValidator,
        resultAssembler,
        aiStatsQueryApi,
        ragTracePersistenceService,
        ragChatService,
        null);
  }

  @Autowired
  public OpenApiQueryServiceImpl(
      KnowledgeRetrievalQueryService knowledgeRetrievalQueryService,
      WorkSpaceQueryService workSpaceQueryService,
      DocumentQueryApi documentQueryApi,
      AiAccessSecurityValidator accessSecurityValidator,
      OpenApiResultAssembler resultAssembler,
      @Nullable AiStatsQueryApi aiStatsQueryApi,
      @Nullable RagTracePersistenceService ragTracePersistenceService,
      @Nullable RagChatService ragChatService,
      @Nullable AiProperties aiProperties) {
    this.knowledgeRetrievalQueryService = knowledgeRetrievalQueryService;
    this.workSpaceQueryService = workSpaceQueryService;
    this.documentQueryApi = documentQueryApi;
    this.accessSecurityValidator = accessSecurityValidator;
    this.resultAssembler = resultAssembler;
    this.aiStatsQueryApi = aiStatsQueryApi;
    this.ragTracePersistenceService = ragTracePersistenceService;
    this.ragChatService = ragChatService;
    this.aiProperties = aiProperties;
  }

  @Override
  public List<McpWorkspaceVO> listWorkspaces(boolean checkScope) {
    LoginUserAuthentication user = SecurityHolder.getLoginUser().orElse(null);
    Long currentUserId = user != null ? user.userId() : null;
    boolean isSystemAdmin = accessSecurityValidator.isSystemAdmin(user);

    OpenApiPrincipal principal = SecurityHolder.getOpenApiPrincipal().orElse(null);
    if (checkScope && principal != null && !principal.hasScope(OpenApiScopeEnum.KB_READ.getCode())) {
      throw BusinessException.forbidden("当前 API Key 未授予 kb:read 权限范围");
    }

    List<WorkSpaceDTO> workspaceDTOs;
    if (isSystemAdmin) {
      workspaceDTOs = workSpaceQueryService.listAllWorkspaces();
    } else if (currentUserId != null) {
      workspaceDTOs = workSpaceQueryService.listMyWorkspaceDTOs(currentUserId);
    } else {
      workspaceDTOs = List.of();
    }

    // 若有白名单限制，求交集收敛
    if (principal != null) {
      workspaceDTOs =
          workspaceDTOs.stream().filter(w -> principal.isWorkspaceAllowed(w.id())).toList();
    }

    return workspaceDTOs.stream()
        .map(
            b -> {
              long totalDocs = documentQueryApi.countDocumentsByWorkspaceId(b.id());
              long totalChunks =
                  aiStatsQueryApi != null ? aiStatsQueryApi.countChunksByWorkspaceId(b.id()) : 0L;
              return new McpWorkspaceVO(b.id(), b.name(), b.description(), totalDocs, totalChunks);
            })
        .toList();
  }

  @Override
  public McpSearchResultVO search(
      String q,
      @Nullable Long workspaceId,
      @Nullable Integer topK,
      @Nullable Double minScore,
      String callSource,
      boolean checkScope) {
    if (StrUtil.isBlank(q)) {
      throw BusinessException.badRequest("检索内容 q 不能为空");
    }

    LoginUserAuthentication user = SecurityHolder.getLoginUser().orElse(null);
    Long currentUserId = user != null ? user.userId() : null;
    boolean isSystemAdmin = accessSecurityValidator.isSystemAdmin(user);

    OpenApiPrincipal principal = SecurityHolder.getOpenApiPrincipal().orElse(null);
    if (checkScope && principal != null && !principal.hasScope(OpenApiScopeEnum.KB_SEARCH.getCode())) {
      throw BusinessException.forbidden("当前 API Key 未授予 kb:search 权限范围");
    }

    Long targetWsId = resolveWorkspaceId(workspaceId, currentUserId, isSystemAdmin, principal);

    // 校验空间白名单限制与空间成员权限
    if (principal != null && !principal.isWorkspaceAllowed(targetWsId)) {
      throw BusinessException.forbidden("当前 API Key 未被允许访问工作空间: " + targetWsId);
    }
    accessSecurityValidator.assertCanAccessWorkspace(targetWsId, currentUserId, isSystemAdmin);

    int resolvedTopK = (topK != null && topK > 0) ? Math.clamp(topK, 1, 50) : 5;
    double resolvedMinScore = minScore != null ? minScore : 0.50;

    String traceId = IdUtil.fastSimpleUUID();
    String apiKey = user != null ? user.apiKey() : null;

    RagTraceContext context =
        new RagTraceContext(traceId, null, targetWsId, currentUserId, q.trim());
    AiCallSourceEnum sourceEnum =
        AiCallSourceEnum.from(callSource, AiCallSourceEnum.OPENAPI);
    String sourceTag = sourceEnum.getCode();
    context.recordCallSource(sourceTag);
    if (StrUtil.isNotBlank(apiKey)) {
      context.recordApiKey(DesensitizeUtils.maskApiKey(apiKey));
    }
    String effectiveModelName =
        aiProperties != null ? aiProperties.resolveEffectiveModelName(principal) : "deepseek-chat";
    String answerTag =
        switch (sourceEnum) {
          case CLI_SKILL -> "[CLI Skill Headless Retrieval: No LLM Generation]";
          case OPENAPI -> "[OpenAPI Headless Retrieval: No LLM Generation]";
          case WEB_CHAT -> "[Web Headless Retrieval: No LLM Generation]";
        };
    context.recordAnswer(answerTag, effectiveModelName);

    try {
      return ScopedValue.where(RagTraceHolder.CURRENT, context)
          .call(
              () -> {
                List<ScoredDocChunkVO> scoredChunks =
                    knowledgeRetrievalQueryService.retrieveScoredChunks(
                        targetWsId, q.trim(), resolvedTopK, resolvedMinScore);

                Set<Long> categoryIds =
                    scoredChunks.stream()
                        .map(ScoredDocChunkVO::chunk)
                        .filter(Objects::nonNull)
                        .map(DocChunkDocument::getCategoryId)
                        .filter(StrUtil::isNotBlank)
                        .map(resultAssembler::parseLongSafe)
                        .filter(Objects::nonNull)
                        .filter(cid -> cid > 0)
                        .collect(Collectors.toSet());

                Map<Long, String> categoryNames = documentQueryApi.getCategoryNames(categoryIds);
                List<McpChunkResultVO> results =
                    resultAssembler.assembleChunkResults(scoredChunks, categoryNames);
                List<CitationVO> citations = resultAssembler.assembleCitations(scoredChunks);
                context.recordCitations(citations);

                if (ragTracePersistenceService != null) {
                  ragTracePersistenceService.asyncSaveTrace(context);
                }

                return new McpSearchResultVO(q.trim(), targetWsId, results.size(), results, traceId);
              });
    } catch (Throwable t) {
      context.markError(t);
      if (ragTracePersistenceService != null) {
        ragTracePersistenceService.asyncSaveTrace(context);
      }
      if (t instanceof BusinessException be) {
        throw be;
      }
      throw new BusinessException("检索失败: " + t.getMessage());
    }
  }

  @Override
  public McpDocumentDetailVO getDocumentDetail(Long id, boolean checkScope) {
    if (id == null) {
      throw BusinessException.badRequest("文档 ID 不能为空");
    }

    DocumentDTO doc =
        documentQueryApi
            .findDocumentById(id)
            .orElseThrow(() -> BusinessException.notFound("文档不存在: " + id));

    LoginUserAuthentication user = SecurityHolder.getLoginUser().orElse(null);
    Long currentUserId = user != null ? user.userId() : null;
    boolean isSystemAdmin = accessSecurityValidator.isSystemAdmin(user);

    OpenApiPrincipal principal = SecurityHolder.getOpenApiPrincipal().orElse(null);
    if (checkScope && principal != null && !principal.hasScope(OpenApiScopeEnum.DOC_READ.getCode())) {
      throw BusinessException.forbidden("当前 API Key 未授予 doc:read 权限范围");
    }

    if (principal != null && !principal.isWorkspaceAllowed(doc.workSpaceId())) {
      throw BusinessException.forbidden("当前 API Key 无权访问该工作空间下的文档");
    }

    accessSecurityValidator.assertCanReadDocument(doc, currentUserId, isSystemAdmin);

    String categoryName = "未分类";
    if (doc.categoryId() != null && doc.categoryId() > 0) {
      categoryName =
          documentQueryApi
              .getCategoryNames(List.of(doc.categoryId()))
              .getOrDefault(doc.categoryId(), "未分类");
    }

    return new McpDocumentDetailVO(
        doc.id(),
        doc.title(),
        doc.workSpaceId(),
        categoryName,
        doc.status() != null ? doc.status().name() : "",
        doc.currentVersion(),
        doc.content(),
        doc.createTime(),
        doc.updateTime());
  }

  @Override
  public AiChatResponse chat(OpenApiChatCompletionRequest request) {
    if (request == null || StrUtil.isBlank(request.message())) {
      throw BusinessException.badRequest("提问内容 message 不能为空");
    }
    if (ragChatService == null) {
      throw new IllegalStateException("RagChatService 未初始化完成");
    }

    AiChatRequest aiReq = prepareAndValidateAiChatRequest(request);
    return ragChatService.chat(aiReq);
  }

  @Override
  public SseEmitter streamChat(OpenApiChatCompletionRequest request) {
    if (request == null || StrUtil.isBlank(request.message())) {
      throw BusinessException.badRequest("提问内容 message 不能为空");
    }
    if (ragChatService == null) {
      throw new IllegalStateException("RagChatService 未初始化完成");
    }

    AiChatRequest aiReq = prepareAndValidateAiChatRequest(request);
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
    ragChatService.streamChat(aiReq, emitter);
    return emitter;
  }

  private AiChatRequest prepareAndValidateAiChatRequest(OpenApiChatCompletionRequest request) {
    LoginUserAuthentication user = SecurityHolder.getLoginUser().orElse(null);
    Long currentUserId = user != null ? user.userId() : null;
    boolean isSystemAdmin = accessSecurityValidator.isSystemAdmin(user);

    OpenApiPrincipal principal = SecurityHolder.getOpenApiPrincipal().orElse(null);
    if (principal != null && !principal.hasScope(OpenApiScopeEnum.CHAT_COMPLETION.getCode())) {
      throw BusinessException.forbidden("当前 API Key 未授予 chat:completion 权限范围");
    }

    Long targetWsId = resolveWorkspaceId(request.workspaceId(), currentUserId, isSystemAdmin, principal);

    if (principal != null && !principal.isWorkspaceAllowed(targetWsId)) {
      throw BusinessException.forbidden("当前 API Key 未被允许访问工作空间: " + targetWsId);
    }

    accessSecurityValidator.assertCanAccessWorkspace(targetWsId, currentUserId, isSystemAdmin);

    return new AiChatRequest(
        request.message().trim(),
        targetWsId,
        request.sessionId(),
        request.enableThinking(),
        request.documentId(),
        request.topK(),
        Collections.emptyMap(),
        null);
  }

  private Long resolveWorkspaceId(
      @Nullable Long workspaceId,
      @Nullable Long currentUserId,
      boolean isSystemAdmin,
      @Nullable OpenApiPrincipal principal) {
    if (workspaceId != null) {
      return workspaceId;
    }

    List<WorkSpaceDTO> availableList;
    if (isSystemAdmin) {
      availableList = workSpaceQueryService.listAllWorkspaces();
    } else if (currentUserId != null) {
      availableList = workSpaceQueryService.listMyWorkspaceDTOs(currentUserId);
    } else {
      availableList = List.of();
    }

    if (principal != null) {
      availableList =
          availableList.stream().filter(w -> principal.isWorkspaceAllowed(w.id())).toList();
    }

    return availableList.stream()
        .map(WorkSpaceDTO::id)
        .filter(Objects::nonNull)
        .findFirst()
        .orElseThrow(() -> BusinessException.badRequest("未指定目标工作空间且当前凭据下无可用空间"));
  }
}
