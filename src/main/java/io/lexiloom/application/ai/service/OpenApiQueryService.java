package io.lexiloom.application.ai.service;

import io.lexiloom.application.ai.model.request.OpenApiChatCompletionRequest;
import io.lexiloom.application.ai.model.response.AiChatResponse;
import io.lexiloom.application.ai.model.vo.McpDocumentDetailVO;
import io.lexiloom.application.ai.model.vo.McpSearchResultVO;
import io.lexiloom.application.ai.model.vo.McpWorkspaceVO;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 开放平台 OpenAPI 与 MCP 核心业务查询编排服务
 */
public interface OpenApiQueryService {

  /**
   * 查询当前调用凭证可访问的工作空间列表
   *
   * @param checkScope 是否强校验 kb:read 权限范围
   * @return 工作空间视图列表
   */
  List<McpWorkspaceVO> listWorkspaces(boolean checkScope);

  /**
   * 纯语义切片检索与 Cross-Encoder 重排
   *
   * @param q 检索词 (必填)
   * @param workspaceId 目标工作空间 ID (可选)
   * @param topK 召回上限 (可选)
   * @param minScore 相似度阈值 (可选)
   * @param callSource 调用来源通道标识 (受管枚举: CLI_SKILL, OPENAPI, WEB_CHAT)
   * @param checkScope 是否强校验 kb:search 权限范围
   * @return 切片检索与重排结果
   */
  McpSearchResultVO search(
      String q,
      @Nullable Long workspaceId,
      @Nullable Integer topK,
      @Nullable Double minScore,
      String callSource,
      boolean checkScope);

  /**
   * 查询单篇长文档完整正文及详情
   *
   * @param id 文档 ID
   * @param checkScope 是否强校验 doc:read 权限范围
   * @return 文档正文视图
   */
  McpDocumentDetailVO getDocumentDetail(Long id, boolean checkScope);

  /**
   * 同步阻塞式执行 OpenAPI 大模型对话与知识问答 (受 BYOK 自带模型密钥驱动)
   *
   * @param request 对话请求体
   * @return 完整问答响应 (包含引用卡片、思考过程与耗时统计)
   */
  AiChatResponse chat(OpenApiChatCompletionRequest request);

  /**
   * 异步流式执行 OpenAPI 大模型对话与知识问答 (SSE 事件流推送)
   *
   * @param request 对话请求体
   * @return SSE 事件发射器通道
   */
  SseEmitter streamChat(OpenApiChatCompletionRequest request);
}
