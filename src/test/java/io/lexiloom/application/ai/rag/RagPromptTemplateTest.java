package io.lexiloom.application.ai.rag;

import static org.assertj.core.api.Assertions.assertThat;

import io.lexiloom.application.ai.gateway.llm.LlmChatCommand;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ChatMessage;
import io.lexiloom.application.ai.model.vo.CitationVO;
import io.lexiloom.application.ai.search.CitationBuilder;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RagPromptTemplateTest {

  private CitationBuilder citationBuilder;
  private RagPromptTemplate ragPromptTemplate;

  @BeforeEach
  void setUp() {
    citationBuilder = new CitationBuilder();
    ragPromptTemplate = new RagPromptTemplate(citationBuilder);
  }

  @Test
  @DisplayName("验证防幻觉系统人设指令完整性")
  void testSystemPromptIntegrity() {
    String systemPrompt = ragPromptTemplate.getSystemPrompt();

    assertThat(systemPrompt)
        .contains("严守参考资料")
        .contains("杜绝幻觉捏造")
        .contains("根据当前知识库参考资料，未找到相关内容，无法为您解答")
        .contains("精准角标溯源")
        .contains("[1]")
        .contains("企业级 AI 智能知识助手");
  }

  @Test
  @DisplayName("验证注入参考资料与用户提问时的 Prompt 组装")
  void testBuildChatCommandWithCitations() {
    CitationVO citation1 =
        new CitationVO(
            1,
            100L,
            "分布式事务规范.pdf",
            "Saga模式设计",
            "Saga 由一系列正向操作和补偿事务构成。",
            0.92,
            3,
            List.of(10f, 20f, 100f, 200f),
            "text",
            "sec_1");
    CitationVO citation2 =
        new CitationVO(
            2,
            100L,
            "分布式事务规范.pdf",
            "TCC模式设计",
            "TCC 包含 Try、Confirm、Cancel 三个阶段。",
            0.85,
            5,
            List.of(15f, 25f, 110f, 210f),
            "text",
            "sec_2");

    List<CitationVO> citations = List.of(citation1, citation2);
    List<ChatMessage> history =
        List.of(
            new ChatMessage("user", "上一轮问题：什么是分布式系统？"),
            new ChatMessage("assistant", "上一轮回答：分布式系统是由多台计算机协同工作的系统。"));

    LlmChatCommand command =
        ragPromptTemplate.buildChatCommand("请比较 Saga 与 TCC 的优缺点", citations, history, true);

    assertThat(command.systemPrompt()).isEqualTo(ragPromptTemplate.getSystemPrompt());
    assertThat(command.enableThinking()).isTrue();
    assertThat(command.history()).hasSize(2);

    String prompt = command.prompt();
    assertThat(prompt)
        .contains("【参考资料】")
        .contains("参考片段 [1]")
        .contains("(来源：《分布式事务规范.pdf》 > 章节: Saga模式设计):")
        .contains("Saga 由一系列正向操作和补偿事务构成。")
        .contains("参考片段 [2]")
        .contains("(来源：《分布式事务规范.pdf》 > 章节: TCC模式设计):")
        .contains("TCC 包含 Try、Confirm、Cancel 三个阶段。")
        .contains("【用户问题】")
        .contains("请比较 Saga 与 TCC 的优缺点");
  }

  @Test
  @DisplayName("验证无参考资料时的降级 Prompt 组装")
  void testBuildChatCommandWithoutCitations() {
    LlmChatCommand command =
        ragPromptTemplate.buildChatCommand("测试提问", List.of(), List.of(), false);

    assertThat(command.enableThinking()).isFalse();
    assertThat(command.history()).isEmpty();
    assertThat(command.prompt()).contains("无相关知识库参考资料").contains("【用户问题】").contains("测试提问");
  }
}
