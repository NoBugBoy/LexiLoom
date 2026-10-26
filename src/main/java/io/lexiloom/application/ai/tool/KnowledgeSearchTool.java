package io.lexiloom.application.ai.tool;

import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ToolDefinition;
import io.lexiloom.application.ai.gateway.rerank.ScoredDocChunkVO;
import io.lexiloom.application.ai.model.vo.CitationVO;
import io.lexiloom.application.ai.rag.KnowledgeRetrievalQueryService;
import io.lexiloom.application.ai.search.CitationBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Model-invoked, read-only search constrained to the already-authorized workspace. */
@Component
@RequiredArgsConstructor
public class KnowledgeSearchTool {
  public static final String NAME = "search_knowledge";
  private static final int MAX_QUERY_LENGTH = 500;
  private static final int MAX_RESULTS = 5;

  private static final ToolDefinition DEFINITION =
      new ToolDefinition(
          NAME,
          "在当前工作空间的知识库中补充检索。仅当现有参考资料不足或需要针对性核实时调用。",
          Map.of(
              "type",
              "object",
              "properties",
              Map.of(
                  "query",
                  Map.of(
                      "type",
                      "string",
                      "description",
                      "简洁、具体的知识库检索问题，避免重复原问题")),
              "required",
              List.of("query"),
              "additionalProperties",
              false));

  private final KnowledgeRetrievalQueryService retrievalQueryService;
  private final CitationBuilder citationBuilder;
  private final ObjectMapper objectMapper;

  public ToolDefinition definition() {
    return DEFINITION;
  }

  public SearchResult execute(String query, Long workspaceId, int firstCitationIndex) {
    if (query == null || query.isBlank() || query.length() > MAX_QUERY_LENGTH) {
      throw new IllegalArgumentException("query 必须为 1 到 500 个字符");
    }
    if (workspaceId == null) {
      throw new IllegalArgumentException("当前工作空间不可用");
    }

    List<ScoredDocChunkVO> matches =
        retrievalQueryService.retrieveScoredChunks(workspaceId, query.trim(), null, null).stream()
            .limit(MAX_RESULTS)
            .toList();
    List<CitationVO> citations = citationBuilder.buildCitations(matches);
    List<CitationVO> indexed = new ArrayList<>(citations.size());
    for (CitationVO citation : citations) {
      indexed.add(
          new CitationVO(
              firstCitationIndex + indexed.size(),
              citation.documentId(),
              citation.docTitle(),
              citation.headerPath(),
              citation.snippet(),
              citation.score(),
              citation.page(),
              citation.bbox(),
              citation.chunkType(),
              citation.anchor(),
              citation.recallSource()));
    }
    return new SearchResult(query.trim(), List.copyOf(indexed));
  }

  public String toToolMessage(SearchResult result) {
    try {
      return objectMapper.writeValueAsString(result);
    } catch (Exception exception) {
      throw new IllegalStateException("无法序列化知识检索结果", exception);
    }
  }

  public record SearchResult(String query, List<CitationVO> results) {}
}
