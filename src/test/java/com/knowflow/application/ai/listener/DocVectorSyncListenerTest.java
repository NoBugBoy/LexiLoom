package com.knowflow.application.ai.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowflow.application.ai.chunk.DeepDocContextEnricher;
import com.knowflow.application.ai.chunk.EnrichedDocChunk;
import com.knowflow.application.ai.gateway.embedding.EmbeddingGateway;
import com.knowflow.application.ai.model.search.DocChunkDocument;
import com.knowflow.application.ai.repository.DocChunkRepository;
import com.knowflow.application.document.api.DocumentQueryApi;
import com.knowflow.application.document.api.dto.DocumentDTO;
import com.knowflow.application.document.model.enums.DocSourceTypeEnum;
import com.knowflow.application.document.model.event.DocumentDeletedEvent;
import com.knowflow.application.document.model.event.DocumentUpdatedEvent;
import com.knowflow.application.document.parser.DocParseStatusEnum;
import com.knowflow.application.document.statemachine.DocumentEventEnum;
import com.knowflow.application.document.statemachine.DocumentStateEnum;
import com.knowflow.application.document.statemachine.DocumentStateTransitionEvent;
import com.knowflow.application.enums.WorkSpaceAclEnum;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DocVectorSyncListenerTest {

  private DocumentQueryApi documentQueryApi;
  private com.knowflow.application.document.api.DocumentStateApi documentStateApi;
  private DeepDocContextEnricher enricher;
  private EmbeddingGateway embeddingGateway;
  private DocChunkRepository docChunkRepository;
  private DocVectorSyncListener listener;

  @BeforeEach
  void setUp() {
    documentQueryApi = mock(DocumentQueryApi.class);
    documentStateApi = mock(com.knowflow.application.document.api.DocumentStateApi.class);
    enricher = mock(DeepDocContextEnricher.class);
    embeddingGateway = mock(EmbeddingGateway.class);
    docChunkRepository = mock(DocChunkRepository.class);

    listener =
        new DocVectorSyncListener(
            documentQueryApi, documentStateApi, enricher, embeddingGateway, docChunkRepository);
  }

  private DocumentDTO createSampleDoc(Long docId, DocumentStateEnum status) {
    return new DocumentDTO(
        docId,
        100L, // workspaceId
        10L, // categoryId
        "系统架构设计",
        "文档摘要",
        "文档正文 Markdown",
        status,
        DocParseStatusEnum.SUCCESS,
        WorkSpaceAclEnum.INTERNAL,
        DocSourceTypeEnum.IMPORT,
        500L, // sourceFileId
        "架构设计.docx",
        1,
        "v1.0",
        1,
        1,
        null,
        1000,
        0,
        0,
        1L,
        LocalDateTime.now(),
        LocalDateTime.now());
  }

  @Test
  @DisplayName("监听状态流转事件：APPROVED 状态下触发切片加冠、向量化与入库")
  void testOnDocumentApprovedSyncsVectors() {
    Long docId = 1L;
    DocumentDTO doc = createSampleDoc(docId, DocumentStateEnum.APPROVED);
    when(documentQueryApi.findDocumentById(docId)).thenReturn(Optional.of(doc));
    when(documentQueryApi.findSourceFileById(500L)).thenReturn(Optional.empty());
    when(documentQueryApi.findTagNamesByDocumentId(docId)).thenReturn(List.of("技术", "架构"));

    EnrichedDocChunk chunk1 =
        EnrichedDocChunk.of(
            0,
            "c_0",
            "系统架构设计",
            "正文",
            "段落1",
            "[帽子1]",
            "[帽子1]\n\n段落1",
            "text",
            1,
            List.of(10f, 20f, 30f, 40f),
            "段落_1");
    when(enricher.enrich(eq(doc), any())).thenReturn(List.of(chunk1));

    float[] dummyVector = new float[1536];
    dummyVector[0] = 0.5f;
    when(embeddingGateway.embedBatch(anyList())).thenReturn(List.of(dummyVector));
    when(embeddingGateway.getDimension()).thenReturn(1536);

    DocumentStateTransitionEvent event =
        new DocumentStateTransitionEvent(
            docId,
            DocumentStateEnum.PENDING_APPROVAL,
            DocumentStateEnum.APPROVED,
            DocumentEventEnum.APPROVE,
            1L,
            "审核通过",
            LocalDateTime.now());

    listener.onDocumentStateTransition(event);

    verify(docChunkRepository).deleteByWorkspaceIdAndDocumentId("100", "1");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<DocChunkDocument>> captor = ArgumentCaptor.forClass(List.class);
    verify(docChunkRepository).saveAll(captor.capture());

    List<DocChunkDocument> saved = captor.getValue();
    assertThat(saved).hasSize(1);
    DocChunkDocument savedChunk = saved.getFirst();
    assertThat(savedChunk.getId()).isEqualTo("1_0");
    assertThat(savedChunk.getWorkspaceId()).isEqualTo("100");
    assertThat(savedChunk.getDocumentId()).isEqualTo("1");
    assertThat(savedChunk.getTitle()).isEqualTo("系统架构设计");
    assertThat(savedChunk.getContent()).isEqualTo("段落1");
    assertThat(savedChunk.getContextPrefix()).isEqualTo("[帽子1]");
    assertThat(savedChunk.getContentVector()).isEqualTo(dummyVector);
    assertThat(savedChunk.getPage()).isEqualTo(1);
    assertThat(savedChunk.getBbox()).containsExactly(10f, 20f, 30f, 40f);
    assertThat(savedChunk.getTags()).containsExactly("技术", "架构");
  }

  @Test
  @DisplayName("监听状态流转事件：REJECTED 状态下物理清理切片索引")
  void testOnDocumentRejectedRemovesChunks() {
    Long docId = 2L;
    DocumentDTO doc = createSampleDoc(docId, DocumentStateEnum.REJECTED);
    when(documentQueryApi.findDocumentById(docId)).thenReturn(Optional.of(doc));

    DocumentStateTransitionEvent event =
        new DocumentStateTransitionEvent(
            docId,
            DocumentStateEnum.PENDING_APPROVAL,
            DocumentStateEnum.REJECTED,
            DocumentEventEnum.REJECT,
            1L,
            "驳回",
            LocalDateTime.now());

    listener.onDocumentStateTransition(event);

    verify(docChunkRepository).deleteByWorkspaceIdAndDocumentId("100", "2");
    verify(docChunkRepository, never()).saveAll(anyList());
  }

  @Test
  @DisplayName("监听文档删除事件：彻底物理清除该文档在 ES 的全部切片")
  void testOnDocumentDeletedRemovesChunks() {
    DocumentDeletedEvent event = new DocumentDeletedEvent(3L, 200L, 1L);

    listener.onDocumentDeleted(event);

    verify(docChunkRepository).deleteByWorkspaceIdAndDocumentId("200", "3");
  }

  @Test
  @DisplayName("监听文档更新事件：已审批通过的文档重新触发向量计算与刷新")
  void testOnDocumentUpdatedRefreshesApprovedDoc() {
    Long docId = 4L;
    DocumentDTO doc = createSampleDoc(docId, DocumentStateEnum.APPROVED);
    when(documentQueryApi.findDocumentById(docId)).thenReturn(Optional.of(doc));
    when(enricher.enrich(eq(doc), any())).thenReturn(List.of());

    DocumentUpdatedEvent event = new DocumentUpdatedEvent(docId, 100L, 1L);

    listener.onDocumentUpdated(event);

    verify(documentQueryApi, org.mockito.Mockito.atLeastOnce()).findDocumentById(docId);
    verify(enricher).enrich(eq(doc), any());
  }

  @Test
  @DisplayName("切块或向量写入异常时，自动触发状态回滚为 PENDING_APPROVAL 并清理切块")
  void testOnDocumentApprovedSyncErrorRollbacksState() {
    Long docId = 5L;
    DocumentDTO doc = createSampleDoc(docId, DocumentStateEnum.APPROVED);
    when(documentQueryApi.findDocumentById(docId)).thenReturn(Optional.of(doc));
    when(enricher.enrich(eq(doc), any())).thenThrow(new RuntimeException("向量计算超时异常"));

    DocumentStateTransitionEvent event =
        new DocumentStateTransitionEvent(
            docId,
            DocumentStateEnum.PENDING_APPROVAL,
            DocumentStateEnum.APPROVED,
            null,
            0L,
            "审批通过",
            java.time.LocalDateTime.now());

    listener.onDocumentStateTransition(event);

    // 验证调用了 rollbackState 回滚为 PENDING_APPROVAL
    verify(documentStateApi)
        .rollbackState(
            eq(docId),
            eq(DocumentStateEnum.PENDING_APPROVAL),
            org.mockito.ArgumentMatchers.contains("自动恢复为待审批"));
    // 验证调用了清理残留切块
    verify(docChunkRepository)
        .deleteByWorkspaceIdAndDocumentId(org.mockito.ArgumentMatchers.any(), eq("5"));
  }
}
