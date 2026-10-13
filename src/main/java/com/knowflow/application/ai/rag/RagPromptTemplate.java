package com.knowflow.application.ai.rag;

import cn.hutool.core.util.StrUtil;
import com.knowflow.application.ai.gateway.llm.LlmChatCommand;
import com.knowflow.application.ai.gateway.llm.LlmChatCommand.ChatMessage;
import com.knowflow.application.ai.model.vo.CitationVO;
import com.knowflow.application.ai.search.CitationBuilder;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * RAG 提示词编排模板组件 (RagPromptTemplate)
 *
 * <p>核心职责：
 *
 * <ol>
 *   <li>注入防幻觉系统人设 (System Persona)，强约束大模型严格基于知识库参考资料回答，严禁臆测虚构；
 *   <li>强制在回答正文中自然标注对应引用来源序号角标 (例如 `[1]`, `[2]`)；
 *   <li>将结构化视觉切片溯源引用转译为 Prompt 参考上下文块；
 *   <li>维护多轮对话历史上下文窗口，组装标准 {@link LlmChatCommand}。
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagPromptTemplate {

  public static final String DEFAULT_SYSTEM_PROMPT =
      """
      你是由 KnowFlow 驱动的企业级 AI 智能知识助手。
      请严格遵循以下准则为用户解答：
      1. 【严守参考资料】：你必须严格基于下方提供的【参考资料】内容回答用户问题。
      2. 【杜绝幻觉捏造】：若参考资料中未包含回答问题所需的事实或信息，请直接且明确地告知用户：“根据当前知识库参考资料，未找到相关内容，无法为您解答”，严禁胡编乱造、主观臆测或借助外部未证实的常识补充细节。
      3. 【精准角标溯源】：当引用或陈述参考资料中的具体事实、数据或观点时，必须在对应陈述的句末自然标注对应的引用来源角标（例如 [1] 或 [1][2]），角标序号必须与【参考资料】中编号严格对应。
      4. 【语言与排版】：使用专业、客观、严谨的中文进行解答，善用 Markdown 列表、加粗与表格增强可读性。
      """;

  private final CitationBuilder citationBuilder;

  /**
   * 组装完整的 LLM 对话执行命令
   *
   * @param userQuestion 用户当前提问
   * @param citations 检索精排召回的引用源列表
   * @param history 多轮对话历史消息
   * @param enableThinking 是否启用深度思考推理 (DeepSeek-R1 / Qwen 等)
   * @return 标准化 LLM 执行命令对象
   */
  public LlmChatCommand buildChatCommand(
      String userQuestion,
      List<CitationVO> citations,
      List<ChatMessage> history,
      boolean enableThinking) {
    String contextText = citationBuilder.formatPromptContext(citations);
    String question = StrUtil.blankToDefault(userQuestion, "").trim();

    String userPrompt =
        String.format(
            """
            【参考资料】
            %s

            【用户问题】
            %s
            """,
            contextText, question);

    return LlmChatCommand.of(userPrompt, DEFAULT_SYSTEM_PROMPT, history, enableThinking);
  }

  /** 获取默认防幻觉系统人设 Prompt */
  public String getSystemPrompt() {
    return DEFAULT_SYSTEM_PROMPT;
  }
}
