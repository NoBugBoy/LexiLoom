package io.lexiloom.application.ai.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.gateway.rerank.ScoredDocChunkVO;
import io.lexiloom.application.ai.model.search.DocChunkDocument;
import io.lexiloom.application.ai.search.HybridSearchCommand;
import io.lexiloom.application.ai.search.HybridSearchQueryService;
import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.enums.WorkSpaceRoleEnum;
import io.lexiloom.application.user.api.WorkSpaceMemberQueryService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class DefaultKnowledgeRetrievalQueryServiceTest {

  @Mock private HybridSearchQueryService hybridSearchQueryService;
  @Mock private WorkSpaceMemberQueryService workSpaceMemberQueryService;

  private AiProperties aiProperties;
  private DefaultKnowledgeRetrievalQueryService queryService;

  @BeforeEach
  void setUp() {
    aiProperties = new AiProperties();
    aiProperties.getRag().setTopK(5);
    aiProperties.getRag().setMinTopK(1);
    aiProperties.getRag().setMaxTopK(20);
    aiProperties.getRag().setSimilarityThreshold(0.60);

    queryService =
        new DefaultKnowledgeRetrievalQueryService(
            hybridSearchQueryService, aiProperties, workSpaceMemberQueryService);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("验证入参为空或空字符串时直接返回空集合")
  void testRetrieveChunksWithBlankParams() {
    assertThat(queryService.retrieveChunks(null, "query", 5, 0.5)).isEmpty();
    assertThat(queryService.retrieveChunks(1L, "  ", 5, 0.5)).isEmpty();
    assertThat(queryService.retrieveScoredChunks(null, "query", 5, 0.5)).isEmpty();
    assertThat(queryService.retrieveScoredChunks(1L, "", 5, 0.5)).isEmpty();
    assertThat(queryService.retrieveScoredChunks((HybridSearchCommand) null)).isEmpty();
  }

  @Test
  @DisplayName("验证纯语义切片检索与 TopK 截断、阈值回退及管理员权限判定")
  void testRetrieveScoredChunksSuccessfully() {
    SecurityHolder.setAuthentication(100L, List.of("ADMIN"));

    DocChunkDocument doc1 =
        DocChunkDocument.builder()
            .id("10_0")
            .chunkId("chk_001")
            .documentId("10")
            .workspaceId("1")
            .title("文档1")
            .content("内容1")
            .build();

    DocChunkDocument doc2 =
        DocChunkDocument.builder()
            .id("20_0")
            .chunkId("chk_002")
            .documentId("20")
            .workspaceId("2")
            .title("文档2")
            .content("内容2")
            .build();

    List<ScoredDocChunkVO> mockResults =
        List.of(ScoredDocChunkVO.of(doc1, 0.95), ScoredDocChunkVO.of(doc2, 0.85));

    when(hybridSearchQueryService.hybridSearch(any(HybridSearchCommand.class)))
        .thenReturn(mockResults);

    // 请求 topK 为 999，应被 clamp 截断为 maxTopK=20；minScore 为 null，回退到 0.60
    List<ScoredDocChunkVO> results =
        queryService.retrieveScoredChunks(1L, "测试问题", 999, null);

    assertThat(results).hasSize(2);
    assertThat(results.get(0).score()).isEqualTo(0.95);
    assertThat(results.get(1).score()).isEqualTo(0.85);

    ArgumentCaptor<HybridSearchCommand> captor = ArgumentCaptor.forClass(HybridSearchCommand.class);
    verify(hybridSearchQueryService).hybridSearch(captor.capture());
    HybridSearchCommand command = captor.getValue();
    assertThat(command.workspaceId()).isEqualTo(1L);
    assertThat(command.query()).isEqualTo("测试问题");
    assertThat(command.topK()).isEqualTo(20);
    assertThat(command.minScore()).isEqualTo(0.60);
    assertThat(command.userId()).isEqualTo(100L);
    assertThat(command.isAdmin()).isTrue();
  }

  @Test
  @DisplayName("验证普通成员空间检索权限判定与 retrieveChunks 映射")
  void testRetrieveChunksWithNormalMember() {
    SecurityHolder.setAuthentication(200L, List.of());
    when(workSpaceMemberQueryService.hasRole(1L, 200L, WorkSpaceRoleEnum.ADMIN)).thenReturn(false);

    DocChunkDocument doc =
        DocChunkDocument.builder()
            .id("15_1")
            .chunkId("chk_015")
            .documentId("15")
            .workspaceId("1")
            .title("规范文档")
            .content("切片段落正文")
            .build();

    when(hybridSearchQueryService.hybridSearch(any(HybridSearchCommand.class)))
        .thenReturn(List.of(ScoredDocChunkVO.of(doc, 0.88)));

    List<DocChunkDocument> chunks = queryService.retrieveChunks(1L, "规范查询", 3, 0.75);

    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).getId()).isEqualTo("15_1");
    assertThat(chunks.get(0).getContent()).isEqualTo("切片段落正文");

    ArgumentCaptor<HybridSearchCommand> captor = ArgumentCaptor.forClass(HybridSearchCommand.class);
    verify(hybridSearchQueryService).hybridSearch(captor.capture());
    assertThat(captor.getValue().isAdmin()).isFalse();
    assertThat(captor.getValue().topK()).isEqualTo(3);
    assertThat(captor.getValue().minScore()).isEqualTo(0.75);
  }
}
