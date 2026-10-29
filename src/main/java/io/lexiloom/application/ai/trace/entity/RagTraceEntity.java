package io.lexiloom.application.ai.trace.entity;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.trace.model.FilterDecision;
import io.lexiloom.application.ai.trace.model.RagTraceContext;
import io.lexiloom.application.ai.trace.model.TraceLatency;
import io.lexiloom.application.common.BaseEntity;
import io.lexiloom.application.enums.AiCallSourceEnum;
import io.lexiloom.application.utils.DesensitizeUtils;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * RAG 对话观测主记录实体 (ll_ai_rag_trace)
 *
 * <p>继承 {@link BaseEntity}，记录单次问答的核心统计指标、分阶段耗时以及用户端点赞/点踩反馈闭环。
 */
@Entity
@Table(
    name = "ll_ai_rag_trace",
    comment = "RAG对话链路观测主表",
    indexes = {
      @Index(name = "idx_rag_trace_id", columnList = "trace_id", unique = true),
      @Index(name = "idx_rag_trace_ws_created", columnList = "workspace_id, create_time"),
      @Index(name = "idx_rag_trace_feedback", columnList = "feedback_rating"),
      @Index(name = "idx_rag_trace_create_time", columnList = "create_time"),
      @Index(name = "idx_rag_trace_api_key", columnList = "api_key")
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RagTraceEntity extends BaseEntity {

  /** 全局唯一问答追踪链路 ID */
  @Column(name = "trace_id", comment = "全局唯一问答追踪链路ID", nullable = false, length = 64, unique = true)
  private String traceId;

  /** 对话会话 ID */
  @Column(name = "session_id", comment = "对话会话ID", length = 64)
  private String sessionId;

  /** 所属团队工作空间 ID */
  @Column(name = "workspace_id", comment = "所属团队工作空间ID", nullable = false)
  private Long workspaceId;

  /** 发起提问的用户 ID */
  @Column(name = "user_id", comment = "发起提问的用户ID")
  private Long userId;

  /** 用户提问原始 Query */
  @Column(name = "query", comment = "用户提问原始Query", nullable = false, length = 1024)
  private String query;

  /** 生成所用大模型版本标识 */
  @Column(name = "model_name", comment = "生成所用大模型版本标识", length = 64)
  private String modelName;

  /** 重排检索策略 (例如 LOCAL_CROSS_ENCODER) */
  @Column(name = "rerank_strategy", comment = "重排检索策略", length = 32)
  private String rerankStrategy;

  /** 初筛候选分片总数 */
  @Column(name = "total_candidates", comment = "初筛候选分片总数")
  private Integer totalCandidates;

  /** 最终入选采纳分片数 */
  @Column(name = "retained_chunks", comment = "最终入选采纳分片数")
  private Integer retainedChunks;

  /** 被过滤淘汰分片数 */
  @Column(name = "filtered_chunks", comment = "被过滤淘汰分片数")
  private Integer filteredChunks;

  /** 向量化耗时 (毫秒) */
  @Column(name = "embedding_time_ms", comment = "向量化耗时(ms)")
  private Long embeddingTimeMs;

  /** 初筛召回耗时 (毫秒) */
  @Column(name = "retrieval_time_ms", comment = "初筛召回耗时(ms)")
  private Long retrievalTimeMs;

  /** 重排序耗时 (毫秒) */
  @Column(name = "rerank_time_ms", comment = "重排序耗时(ms)")
  private Long rerankTimeMs;

  /** 模型回答生成耗时 (毫秒) */
  @Column(name = "generation_time_ms", comment = "模型回答生成耗时(ms)")
  private Long generationTimeMs;

  /** 全链路端到端总耗时 (毫秒) */
  @Column(name = "total_time_ms", comment = "全链路端到端总耗时(ms)")
  private Long totalTimeMs;

  /** LLM Prompt 提问输入真实消耗 Token 数 */
  @Column(name = "prompt_tokens", comment = "LLM输入Prompt真实Token消耗")
  private Integer promptTokens = 0;

  /** LLM Completion 回答生成真实消耗 Token 数 */
  @Column(name = "completion_tokens", comment = "LLM回答生成真实Token消耗")
  private Integer completionTokens = 0;

  /** 向量化实际消耗 Token 数 (非估算) */
  @Column(name = "embedding_tokens", comment = "向量化实际消耗Token数")
  private Integer embeddingTokens = 0;

  /** 重排序实际消耗 Token 数 (非估算，本地RRF/NONE为0) */
  @Column(name = "rerank_tokens", comment = "重排序实际消耗Token数")
  private Integer rerankTokens = 0;

  /** 全链路端到端真实总 Token 消耗 (embedding + rerank + prompt + completion) */
  @Column(name = "total_tokens", comment = "全链路端到端真实总Token消耗")
  private Integer totalTokens = 0;

  /** 用户反馈评价：0 未评价，1 点赞，-1 点踩 */
  @Column(name = "feedback_rating", comment = "用户反馈评价(0:未评价, 1:点赞, -1:点踩)")
  private Integer feedbackRating = 0;

  /** 反馈标签集合 (逗号分隔，如 ACCURATE,HELPFUL) */
  @Column(name = "feedback_tags", comment = "反馈标签集合(如ACCURATE,HELPFUL)", length = 255)
  private String feedbackTags;

  /** 用户详细反馈文本意见 */
  @Column(name = "feedback_comment", comment = "用户详细反馈文本意见", length = 1024)
  private String feedbackComment;

  /** 调用来源 (CLI_SKILL, OPENAPI, WEB_CHAT) */
  @Column(name = "call_source", comment = "调用来源(CLI_SKILL/OPENAPI/WEB_CHAT)", length = 32)
  private String callSource = "WEB_CHAT";

  /** 调用凭证 API Key (例如 lx_live_xxx 或脱敏前缀) */
  @Column(name = "api_key", comment = "调用凭证API Key", length = 128)
  private String apiKey;

  /**
   * 充血领域工厂方法：基于运行期观测上下文内聚计算候选采纳率、阶段耗时与凭证脱敏并构建主表实体
   *
   * @param context 运行期全链路上下文对象
   * @return 组装完成的 {@link RagTraceEntity}
   */
  public static RagTraceEntity fromContext(RagTraceContext context) {
    Objects.requireNonNull(context, "traceContext 不能为空");
    TraceLatency latency = context.buildLatency();
    int totalCandidates = context.getCandidates().size();
    int retainedChunks =
        (int)
            context.getCandidates().stream()
                .filter(c -> c.decision() == FilterDecision.ACCEPTED)
                .count();
    int filteredChunks = Math.max(0, totalCandidates - retainedChunks);

    return create(
        context.getTraceId(),
        context.getSessionId(),
        context.getWorkspaceId(),
        context.getUserId(),
        context.getQuery(),
        context.getModelName(),
        context.getRerankStrategy(),
        totalCandidates,
        retainedChunks,
        filteredChunks,
        latency.embeddingMs(),
        latency.retrievalMs(),
        latency.rerankMs(),
        latency.generationMs(),
        latency.totalMs(),
        context.getPromptTokens(),
        context.getCompletionTokens(),
        context.getEmbeddingTokens(),
        context.getRerankTokens(),
        context.getTotalTokens(),
        context.getCallSource(),
        context.getApiKey());
  }

  public static RagTraceEntity create(
      String traceId,
      String sessionId,
      Long workspaceId,
      Long userId,
      String query,
      String modelName,
      String rerankStrategy,
      int totalCandidates,
      int retainedChunks,
      int filteredChunks,
      long embeddingTimeMs,
      long retrievalTimeMs,
      long rerankTimeMs,
      long generationTimeMs,
      long totalTimeMs,
      int promptTokens,
      int completionTokens,
      int embeddingTokens,
      int rerankTokens,
      int totalTokens) {

    RagTraceEntity entity = new RagTraceEntity();
    entity.traceId = Objects.requireNonNull(traceId, "traceId不能为空");
    entity.sessionId = sessionId;
    entity.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId不能为空");
    entity.userId = userId;
    entity.query = query != null ? query.trim() : "";
    entity.modelName = modelName;
    entity.rerankStrategy = rerankStrategy;
    entity.totalCandidates = totalCandidates;
    entity.retainedChunks = retainedChunks;
    entity.filteredChunks = filteredChunks;
    entity.embeddingTimeMs = embeddingTimeMs;
    entity.retrievalTimeMs = retrievalTimeMs;
    entity.rerankTimeMs = rerankTimeMs;
    entity.generationTimeMs = generationTimeMs;
    entity.totalTimeMs = totalTimeMs;
    entity.promptTokens = promptTokens;
    entity.completionTokens = completionTokens;
    entity.embeddingTokens = embeddingTokens;
    entity.rerankTokens = rerankTokens;
    entity.totalTokens = totalTokens;
    entity.feedbackRating = 0;
    entity.setCreateTime(LocalDateTime.now());
    entity.setUpdateTime(entity.getCreateTime());
    entity.setCreateBy(userId);
    entity.setUpdateBy(userId);
    return entity;
  }

  public static RagTraceEntity create(
      String traceId,
      String sessionId,
      Long workspaceId,
      Long userId,
      String query,
      String modelName,
      String rerankStrategy,
      int totalCandidates,
      int retainedChunks,
      int filteredChunks,
      long embeddingTimeMs,
      long retrievalTimeMs,
      long rerankTimeMs,
      long generationTimeMs,
      long totalTimeMs,
      int promptTokens,
      int completionTokens,
      int embeddingTokens,
      int rerankTokens,
      int totalTokens,
      String callSource) {

    return create(
        traceId,
        sessionId,
        workspaceId,
        userId,
        query,
        modelName,
        rerankStrategy,
        totalCandidates,
        retainedChunks,
        filteredChunks,
        embeddingTimeMs,
        retrievalTimeMs,
        rerankTimeMs,
        generationTimeMs,
        totalTimeMs,
        promptTokens,
        completionTokens,
        embeddingTokens,
        rerankTokens,
        totalTokens,
        callSource,
        null);
  }

  public static RagTraceEntity create(
      String traceId,
      String sessionId,
      Long workspaceId,
      Long userId,
      String query,
      String modelName,
      String rerankStrategy,
      int totalCandidates,
      int retainedChunks,
      int filteredChunks,
      long embeddingTimeMs,
      long retrievalTimeMs,
      long rerankTimeMs,
      long generationTimeMs,
      long totalTimeMs,
      int promptTokens,
      int completionTokens,
      int embeddingTokens,
      int rerankTokens,
      int totalTokens,
      String callSource,
      String apiKey) {

    RagTraceEntity entity =
        create(
            traceId,
            sessionId,
            workspaceId,
            userId,
            query,
            modelName,
            rerankStrategy,
            totalCandidates,
            retainedChunks,
            filteredChunks,
            embeddingTimeMs,
            retrievalTimeMs,
            rerankTimeMs,
            generationTimeMs,
            totalTimeMs,
            promptTokens,
            completionTokens,
            embeddingTokens,
            rerankTokens,
            totalTokens);
    if (StrUtil.isNotBlank(callSource)) {
      entity.callSource = AiCallSourceEnum.from(callSource, AiCallSourceEnum.WEB_CHAT).getCode();
    }
    if (StrUtil.isNotBlank(apiKey)) {
      entity.apiKey = DesensitizeUtils.maskApiKey(apiKey);
    }
    return entity;
  }

  public static RagTraceEntity create(
      String traceId,
      String sessionId,
      Long workspaceId,
      Long userId,
      String query,
      String modelName,
      String rerankStrategy,
      int totalCandidates,
      int retainedChunks,
      int filteredChunks,
      long embeddingTimeMs,
      long retrievalTimeMs,
      long rerankTimeMs,
      long generationTimeMs,
      long totalTimeMs) {

    return create(
        traceId,
        sessionId,
        workspaceId,
        userId,
        query,
        modelName,
        rerankStrategy,
        totalCandidates,
        retainedChunks,
        filteredChunks,
        embeddingTimeMs,
        retrievalTimeMs,
        rerankTimeMs,
        generationTimeMs,
        totalTimeMs,
        0,
        0,
        0,
        0,
        0);
  }

  /** 获取经安全脱敏后的 API Key (供接口返回及日志打印) */
  public String getApiKey() {
    return DesensitizeUtils.maskApiKey(this.apiKey);
  }

  /** 更新用户反馈 */
  public void recordFeedback(int rating, String tags, String comment) {
    this.feedbackRating = rating;
    this.feedbackTags = tags;
    this.feedbackComment = comment;
    this.setUpdateTime(LocalDateTime.now());
  }
}
