package io.lexiloom.application.ai.rag;

import io.lexiloom.application.ai.gateway.llm.LlmChatCommand;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ChatMessage;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ToolCall;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ToolChatResult;
import io.lexiloom.application.ai.gateway.llm.LlmGateway;
import io.lexiloom.application.ai.model.response.AiChatResponse;
import io.lexiloom.application.ai.model.vo.CitationVO;
import io.lexiloom.application.ai.tool.KnowledgeSearchTool;
import io.lexiloom.application.ai.trace.RagTraceHolder;
import io.lexiloom.application.ai.trace.model.RagTraceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * 同步 RAG 多轮工具调用执行器 (RagToolChatExecutor)
 *
 * <p>封装同步对话下的 Function Calling 状态循环、安全上限控制与引用累加。
 */
@Slf4j
final class RagToolChatExecutor {

  static final int MAX_KNOWLEDGE_TOOL_CALLS_PER_TURN = 3;

  private final LlmGateway llmGateway;
  @Nullable private final KnowledgeSearchTool knowledgeSearchTool;
  @Nullable private final ObjectMapper objectMapper;

  RagToolChatExecutor(
      LlmGateway llmGateway,
      @Nullable KnowledgeSearchTool knowledgeSearchTool,
      @Nullable ObjectMapper objectMapper) {
    this.llmGateway = llmGateway;
    this.knowledgeSearchTool = knowledgeSearchTool;
    this.objectMapper = objectMapper;
  }

  ToolingChatResult execute(
      LlmChatCommand command,
      List<ChatMessage> history,
      Long workspaceId,
      List<CitationVO> initialCitations) {
    if (knowledgeSearchTool == null || objectMapper == null) {
      return ToolingChatResult.from(llmGateway.chat(command), initialCitations);
    }

    String toolInstruction =
        "\n\n当参考资料不足以回答，或需要针对某个条件进一步核实时，可以调用 search_knowledge。"
            + "你可以根据已有检索结果继续发起不同问题的检索，直到证据充分后再回答；"
            + "服务端设有每轮工具调用安全上限。"
            + "工具只检索当前工作空间: "
            + workspaceId;
    LlmChatCommand toolCommand =
        new LlmChatCommand(
            command.prompt(),
            command.systemPrompt() + toolInstruction,
            history,
            command.enableThinking(),
            command.temperature(),
            command.maxTokens());
    List<ChatMessage> conversation = new ArrayList<>(history);
    conversation.add(new ChatMessage("user", command.prompt()));
    List<CitationVO> citations = new ArrayList<>(initialCitations);
    long promptTokens = 0;
    long completionTokens = 0;
    boolean hasUsage = false;
    long startedAt = System.currentTimeMillis();
    String answer;
    String thinking;
    int executedToolCalls = 0;
    boolean toolsDisabled = false;

    while (true) {
      ToolChatResult result =
          llmGateway.chatWithTools(
              toolCommand,
              conversation,
              toolsDisabled ? List.of() : List.of(knowledgeSearchTool.definition()));
      if (result.usage() != null) {
        hasUsage = true;
        promptTokens += nullToZero(result.usage().promptTokens());
        completionTokens += nullToZero(result.usage().completionTokens());
      }
      if (result.toolCalls().isEmpty()) {
        answer = result.content();
        thinking = result.thinkingContent();
        break;
      }

      if (toolsDisabled) {
        throw new IllegalStateException("模型在工具权限关闭后仍返回工具调用");
      }

      conversation.add(new ChatMessage("assistant", result.content(), null, result.toolCalls()));
      for (ToolCall call : result.toolCalls()) {
        String toolOutput;
        if (executedToolCalls >= MAX_KNOWLEDGE_TOOL_CALLS_PER_TURN) {
          toolOutput = toolError(objectMapper, "本轮对话已达到知识检索工具调用上限");
        } else if (!KnowledgeSearchTool.NAME.equals(call.name())) {
          toolOutput = toolError(objectMapper, "不支持的工具");
        } else {
          executedToolCalls++;
          try {
            var arguments = objectMapper.readTree(call.arguments());
            String query =
                arguments != null
                        && arguments.hasNonNull("query")
                        && arguments.get("query").isString()
                    ? arguments.get("query").asString()
                    : null;
            KnowledgeSearchTool.SearchResult searchResult =
                knowledgeSearchTool.execute(query, workspaceId, citations.size() + 1);
            citations.addAll(searchResult.results());
            toolOutput = knowledgeSearchTool.toToolMessage(searchResult);
            log.info(
                "【RagChatService】执行知识检索工具: wsId={}, hits={}",
                workspaceId,
                searchResult.results().size());
          } catch (Exception exception) {
            log.warn(
                "【RagChatService】知识检索工具执行失败: wsId={}, cause={}",
                workspaceId,
                exception.getClass().getSimpleName());
            toolOutput = toolError(objectMapper, "检索参数无效或知识库暂不可用，请调整问题后重试");
          }
        }
        conversation.add(new ChatMessage("tool", toolOutput, call.id(), List.of()));
      }
      if (executedToolCalls >= MAX_KNOWLEDGE_TOOL_CALLS_PER_TURN) {
        toolsDisabled = true;
      }
    }

    int prompt = hasUsage ? Math.toIntExact(Math.min(promptTokens, Integer.MAX_VALUE)) : 0;
    int completion = hasUsage ? Math.toIntExact(Math.min(completionTokens, Integer.MAX_VALUE)) : 0;
    if (hasUsage) {
      RagTraceContext traceContext = RagTraceHolder.get();
      if (traceContext != null) {
        traceContext.recordLlmTokens((long) prompt, (long) completion);
      }
    }
    AiChatResponse response =
        AiChatResponse.of(
            answer,
            thinking,
            List.of(),
            System.currentTimeMillis() - startedAt,
            null,
            null,
            hasUsage ? prompt : null,
            hasUsage ? completion : null,
            hasUsage ? (int) Math.min((long) prompt + completion, Integer.MAX_VALUE) : null);
    return new ToolingChatResult(response, List.copyOf(citations));
  }

  static String toolError(@Nullable ObjectMapper objectMapper, String message) {
    if (objectMapper == null) {
      return "{\"error\":\"tool execution failed\"}";
    }
    try {
      return objectMapper.writeValueAsString(Map.of("error", message));
    } catch (Exception exception) {
      return "{\"error\":\"tool execution failed\"}";
    }
  }

  private static long nullToZero(@Nullable Long value) {
    return value == null ? 0 : value;
  }

  record ToolingChatResult(AiChatResponse response, List<CitationVO> citations) {
    static ToolingChatResult from(AiChatResponse response, List<CitationVO> initialCitations) {
      return new ToolingChatResult(response, initialCitations);
    }
  }
}
