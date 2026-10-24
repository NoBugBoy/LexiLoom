package io.lexiloom.application.ai.trace.entity;

import io.lexiloom.application.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * RAG 过程快照明细实体 (不可变审计记录, ll_ai_rag_trace_detail)
 *
 * <p>继承 {@link BaseEntity}，遵循领域建模不可变快照原则，包含初筛全量切片及过滤因果快照、选拔引用源全貌及大模型最终回答。
 */
@Entity
@Immutable
@Table(
    name = "ll_ai_rag_trace_detail",
    comment = "RAG对话链路快照明细表",
    indexes = {@Index(name = "idx_rag_trace_detail_tid", columnList = "trace_id", unique = true)})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RagTraceDetailEntity extends BaseEntity {

  /** 全局唯一问答追踪链路 ID */
  @Column(name = "trace_id", comment = "全局唯一问答追踪链路ID", nullable = false, length = 64, unique = true)
  private String traceId;

  /** 初筛候选分片及过滤原因快照 JSON */
  @Column(
      name = "candidate_snapshot_json",
      comment = "初筛候选分片及过滤原因快照JSON",
      columnDefinition = "TEXT")
  private String candidateSnapshotJson;

  /** 最终入选引用来源及文档分片详情 JSON */
  @Column(name = "citations_json", comment = "最终引用来源及文档分片详情JSON", columnDefinition = "TEXT")
  private String citationsJson;

  /** 大模型最终完整回答文本 */
  @Column(name = "answer_content", comment = "大模型最终完整回答内容", columnDefinition = "TEXT")
  private String answerContent;

  public static RagTraceDetailEntity create(
      String traceId, String candidateSnapshotJson, String citationsJson, String answerContent) {

    RagTraceDetailEntity entity = new RagTraceDetailEntity();
    entity.traceId = Objects.requireNonNull(traceId, "traceId不能为空");
    entity.candidateSnapshotJson = candidateSnapshotJson;
    entity.citationsJson = citationsJson;
    entity.answerContent = answerContent;
    LocalDateTime now = LocalDateTime.now();
    entity.setCreateTime(now);
    entity.setUpdateTime(now);
    return entity;
  }
}
