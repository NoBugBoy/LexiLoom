package io.lexiloom.application.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.lexiloom.application.ai.gateway.rerank.ScoredDocChunkVO;
import io.lexiloom.application.ai.model.search.DocChunkDocument;
import io.lexiloom.application.ai.model.vo.CitationVO;
import io.lexiloom.application.ai.rag.KnowledgeRetrievalQueryService;
import io.lexiloom.application.ai.search.CitationBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class KnowledgeSearchToolTest {

  @Mock private KnowledgeRetrievalQueryService retrievalQueryService;
  @Mock private CitationBuilder citationBuilder;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void rejectsInvalidInputsBeforeRetrieval() {
    KnowledgeSearchTool tool = new KnowledgeSearchTool(retrievalQueryService, citationBuilder, objectMapper);

    assertThatThrownBy(() -> tool.execute(" ", 10L, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tool.execute("query", null, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tool.execute("x".repeat(501), 10L, 1))
        .isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(retrievalQueryService, citationBuilder);
  }

  @Test
  void searchesOnlySuppliedWorkspaceAndReturnsCitationReferences() {
    KnowledgeSearchTool tool = new KnowledgeSearchTool(retrievalQueryService, citationBuilder, objectMapper);
    when(retrievalQueryService.retrieveScoredChunks(10L, "specific question", null, null))
        .thenReturn(List.of(new ScoredDocChunkVO(new DocChunkDocument(), 0.9)));
    when(citationBuilder.buildCitations(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            List.of(
                new CitationVO(
                    1,
                    77L,
                    "政策文档",
                    "退款",
                    "具体退款规则",
                    0.9,
                    2,
                    List.of(),
                    "text",
                    "anchor")));

    KnowledgeSearchTool.SearchResult result = tool.execute(" specific question ", 10L, 3);

    assertThat(result.query()).isEqualTo("specific question");
    assertThat(result.results()).hasSize(1);
    assertThat(result.results().getFirst().citationIndex()).isEqualTo(3);
    assertThat(tool.toToolMessage(result)).contains("政策文档", "具体退款规则");
    verify(retrievalQueryService).retrieveScoredChunks(10L, "specific question", null, null);
  }

  @Test
  void toolDefinitionDoesNotAllowWorkspaceSelection() {
    var definition = new KnowledgeSearchTool(retrievalQueryService, citationBuilder, objectMapper).definition();

    assertThat(definition.name()).isEqualTo("search_knowledge");
    assertThat(definition.parameters()).containsEntry("additionalProperties", false);
    assertThat(definition.parameters().get("properties").toString()).contains("query").doesNotContain("workspaceId");
  }
}
