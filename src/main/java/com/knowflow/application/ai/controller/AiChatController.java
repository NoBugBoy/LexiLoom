package com.knowflow.application.ai.controller;

import com.knowflow.application.ai.listener.DocVectorSyncListener;
import com.knowflow.application.ai.model.request.AiChatRequest;
import com.knowflow.application.ai.model.request.ConversationSessionCreateRequest;
import com.knowflow.application.ai.model.response.AiChatResponse;
import com.knowflow.application.ai.model.response.AiReindexResponse;
import com.knowflow.application.ai.model.search.DocChunkDocument;
import com.knowflow.application.ai.model.vo.ConversationMessageVO;
import com.knowflow.application.ai.model.vo.ConversationSessionVO;
import com.knowflow.application.ai.model.vo.DocChunkVO;
import com.knowflow.application.ai.rag.ConversationSessionService;
import com.knowflow.application.ai.rag.RagChatService;
import com.knowflow.application.ai.repository.DocChunkRepository;
import com.knowflow.application.common.ErrorCode;
import com.knowflow.application.common.LoginUserAuthentication;
import com.knowflow.application.common.SecurityHolder;
import com.knowflow.application.document.api.DocumentQueryApi;
import com.knowflow.application.document.api.dto.DocumentDTO;
import com.knowflow.application.document.statemachine.DocumentStateEnum;
import com.knowflow.application.exception.BusinessException;
import com.knowflow.application.user.api.WorkSpaceMemberQueryService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
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
  private final WorkSpaceMemberQueryService workSpaceMemberQueryService;

  /**
   * SSE 流式对话接口（核心端点）
   *
   * <p>按序流式推送事件：
   *
   * <ul>
   *   <li>{@code event: citation} 视觉溯源几何坐标引用数组
   *   <li>{@code event: thinking} 深度推理思维链 Token
   *   <li>{@code event: message} 回答正文增量 Token
   *   <li>{@code event: done} 对话生成完成标记
   * </ul>
   *
   * @param request AI 对话请求参数
   * @return SSE 异步发射器通道
   */
  @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamChat(@Valid @RequestBody AiChatRequest request) {
    assertCanAccessWorkspace(request.workspaceId());
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
    assertCanAccessWorkspace(request.workspaceId());
    validateChatDocumentScope(request);

    return ragChatService.chat(request);
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
    assertCanAccessWorkspace(workspaceId);
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
    assertCanAccessWorkspace(request.workspaceId());
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
        .ifPresent(meta -> assertCanAccessWorkspace(meta.workspaceId()));

    return conversationSessionService.getFullHistory(sessionId);
  }

  /**
   * 删除指定会话（销毁消息、元数据及索引）
   *
   * @param sessionId 会话唯一标识
   */
  @DeleteMapping("/sessions/{sessionId}")
  public void deleteSession(@PathVariable("sessionId") String sessionId) {
    if (!conversationSessionService.sessionExists(sessionId)) {
      throw new BusinessException(ErrorCode.Ai.SESSION_NOT_FOUND);
    }
    conversationSessionService
        .getSessionMeta(sessionId)
        .ifPresent(meta -> assertCanAccessWorkspace(meta.workspaceId()));

    Long userId = getCurrentUserId();
    conversationSessionService.deleteSession(sessionId, userId);
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

    assertCanAccessWorkspace(doc.workSpaceId());

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

    assertCanAccessWorkspace(doc.workSpaceId());

    if (doc.status() != DocumentStateEnum.APPROVED) {
      throw BusinessException.badRequest("仅允许对已审批通过(APPROVED)状态的文档重建向量索引");
    }

    docVectorSyncListener.syncDocumentChunks(docId);

    long count =
        docChunkRepository.countByWorkspaceIdAndDocumentId(
            String.valueOf(doc.workSpaceId()), String.valueOf(docId));

    return AiReindexResponse.success(docId, count);
  }

  private void assertCanAccessWorkspace(Long workspaceId) {
    if (workspaceId == null) {
      return;
    }
    SecurityHolder.getLoginUser()
        .ifPresent(
            user -> {
              if (workSpaceMemberQueryService != null
                  && !workSpaceMemberQueryService.isMember(workspaceId, user.userId())) {
                throw BusinessException.forbidden("您不是该工作空间成员，无权访问");
              }
            });
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
    }
  }

  private Long getCurrentUserId() {
    return SecurityHolder.getLoginUser()
        .map(LoginUserAuthentication::userId)
        .orElse(null);
  }
}
