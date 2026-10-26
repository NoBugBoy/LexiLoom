package io.lexiloom.application.ai.controller.openapi;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.model.request.OpenApiChatCompletionRequest;
import io.lexiloom.application.ai.model.request.OpenApiKnowledgeSearchRequest;
import io.lexiloom.application.ai.model.response.AiChatResponse;
import io.lexiloom.application.ai.model.vo.McpDocumentDetailVO;
import io.lexiloom.application.ai.model.vo.McpSearchResultVO;
import io.lexiloom.application.ai.model.vo.McpWorkspaceVO;
import io.lexiloom.application.ai.service.OpenApiQueryService;
import io.lexiloom.application.enums.AiCallSourceEnum;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ClassPathResource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 开放平台 OpenAPI 核心 REST 控制器
 *
 * <p>为外部业务系统 (OA, CRM, SaaS) 与企业级 Agent 提供标准化 OpenAPI 接口：
 *
 * <ul>
 *   <li>可用工作空间列表只读发现 (受 API Key 绑定的空间白名单与用户权限约束)；
 *   <li>语义切片多路召回与 Cross-Encoder 重排精排 (由平台统一算力与 Token 承担)；
 *   <li>单篇规范长文档完整正文提取；
 *   <li>大模型对话补全与 RAG 知识库问答 (支持流式 SSE 与同步模式，受 BYOK 自带模型密钥驱动)。
 * </ul>
 */
@Slf4j
@Validated
@RestController
@RequestMapping({"/api/v1/openapi", "/v1/openapi"})
@RequiredArgsConstructor
public class OpenApiController {

  private final OpenApiQueryService openApiQueryService;

  /**
   * 查询当前 OpenAPI 凭据有权限访问的工作空间列表
   *
   * @return 工作空间视图列表
   */
  @GetMapping("/workspaces")
  public List<McpWorkspaceVO> listWorkspaces() {
    return openApiQueryService.listWorkspaces(true);
  }

  /** 返回 OpenAPI 接口 Markdown 文档。 */
  @GetMapping(value = "/docs", produces = "text/markdown;charset=UTF-8")
  public ResponseEntity<String> getApiDocumentation() throws IOException {
    ClassPathResource resource = new ClassPathResource("openapi/openapi-api.md");
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
        .body(resource.getContentAsString(StandardCharsets.UTF_8));
  }

  /**
   * 知识库语义检索与 Cross-Encoder 重排接口 (POST 请求体方式，推荐)
   *
   * @param headerSource 请求头调用来源 (可选，受管值: CLI_SKILL, OPENAPI, WEB_CHAT)
   * @param request 检索请求体
   * @return 召回切片精排结果列表
   */
  @PostMapping("/knowledge/search")
  public McpSearchResultVO searchPost(
      @org.springframework.web.bind.annotation.RequestHeader(
              value = "X-Call-Source",
              required = false)
          String headerSource,
      @Valid @RequestBody OpenApiKnowledgeSearchRequest request) {
    String callSource = resolveCallSource(headerSource);
    return openApiQueryService.search(
        request.q(), request.workspaceId(), request.topK(), request.minScore(), callSource, true);
  }

  /**
   * 知识库语义检索与 Cross-Encoder 重排接口 (GET Query 参数方式，供快速联调与脚本测试)
   *
   * @param headerSource 请求头自定义调用来源 (可选)
   * @param querySource Query 参数调用来源 (可选)
   * @param q 自然语言 Query (必填)
   * @param workspaceId 目标工作空间 ID (可选)
   * @param topK 召回切片数量上限 (可选，默认 5)
   * @param minScore 相似度阈值 (可选，默认 0.50)
   * @return 召回切片精排结果列表
   */
  @GetMapping("/knowledge/search")
  public McpSearchResultVO searchGet(
      @org.springframework.web.bind.annotation.RequestHeader(
              value = "X-Call-Source",
              required = false)
          String headerSource,
      @RequestParam(value = "callSource", required = false) String querySource,
      @RequestParam("q") @NotBlank(message = "检索内容 q 不能为空") String q,
      @RequestParam(value = "workspaceId", required = false) Long workspaceId,
      @RequestParam(value = "topK", required = false) Integer topK,
      @RequestParam(value = "minScore", required = false) Double minScore) {
    String callSource =
        resolveCallSource(StrUtil.isNotBlank(querySource) ? querySource : headerSource);
    return openApiQueryService.search(q, workspaceId, topK, minScore, callSource, true);
  }

  private String resolveCallSource(String customSource) {
    return AiCallSourceEnum.from(customSource, AiCallSourceEnum.OPENAPI).getCode();
  }

  /**
   * 获取单篇长文档完整 Markdown 正文与版本状态详情
   *
   * @param id 文档主键 ID
   * @return 文档正文视图
   */
  @GetMapping("/documents/{id}")
  public McpDocumentDetailVO getDocumentDetail(@PathVariable("id") Long id) {
    return openApiQueryService.getDocumentDetail(id, true);
  }

  /**
   * 大模型对话与知识库问答统一入口 (兼容 OpenAI 风格 /chat/completions)
   *
   * <p>根据 {@code stream} 参数智能分派：
   *
   * <ul>
   *   <li>{@code stream = true}：返回 SSE 流式响应 (text/event-stream)
   *   <li>{@code stream = false}：同步阻塞返回完整 JSON 对象
   * </ul>
   *
   * @param request 对话补全请求体
   * @return 动态响应 (SseEmitter 或 AiChatResponse)
   */
  @PostMapping("/chat/completions")
  public Object chatCompletions(@Valid @RequestBody OpenApiChatCompletionRequest request) {
    if (request.isStream()) {
      return openApiQueryService.streamChat(request);
    }
    return openApiQueryService.chat(request);
  }

  /**
   * SSE 异步流式问答通道
   *
   * <p>流式事件包括 {@code thinking}、{@code message}、{@code citation}、{@code tools} 和 {@code done}；
   * 工具过程数据以 {@code <tools>...</tools>} 标记，仅包含工具状态，不包含完整检索片段。
   *
   * @param request 对话请求体
   * @return SSE 事件发射器通道
   */
  @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamChat(@Valid @RequestBody OpenApiChatCompletionRequest request) {
    return openApiQueryService.streamChat(request);
  }

  /**
   * 同步阻塞式问答通道
   *
   * @param request 对话请求体
   * @return 完整问答回复实体
   */
  @PostMapping("/chat")
  public AiChatResponse chat(@Valid @RequestBody OpenApiChatCompletionRequest request) {
    return openApiQueryService.chat(request);
  }
}
