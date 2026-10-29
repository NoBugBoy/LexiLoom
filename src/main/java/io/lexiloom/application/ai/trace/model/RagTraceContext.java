package io.lexiloom.application.ai.trace.model;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.model.vo.CitationVO;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import lombok.Getter;

/**
 * 运行期 RAG 全链路上下文聚合对象
 *
 * <p>绑定于 JDK 25 的 {@code ScopedValue} 作用域中，贯穿请求入口、初筛检索、过滤重排与模型生成。 纯内存收集，无锁或轻量线程安全，单向传递，生命周期随请求结束销毁。
 */
@Getter
public class RagTraceContext {

  private final String traceId;
  private volatile String sessionId;
  private final Long workspaceId;
  private final Long userId;
  private final String query;
  private final long startMs;

  // 阶段耗时统计 (ms)
  private final AtomicLong embeddingMs = new AtomicLong(0);
  private final AtomicLong retrievalMs = new AtomicLong(0);
  private final AtomicLong rerankMs = new AtomicLong(0);
  private final AtomicLong generationMs = new AtomicLong(0);

  // 初筛全量候选及过滤因果列表 (保留所有生死切片)
  private final List<CandidateTraceItem> candidates = new CopyOnWriteArrayList<>();

  // 最终入选引用源
  private volatile List<CitationVO> citations = List.of();

  // 模型输出产物 (排除 thinkingContent)
  private volatile String modelName;
  private volatile String rerankStrategy;
  private volatile String answerContent;
  private final AtomicLong embeddingTokens = new AtomicLong(0);
  private final AtomicLong rerankTokens = new AtomicLong(0);
  private final AtomicLong promptTokens = new AtomicLong(0);
  private final AtomicLong completionTokens = new AtomicLong(0);
  private volatile String callSource = "WEB_CHAT";
  private volatile String apiKey;
  private volatile String errorMessage;

  public RagTraceContext(
      String traceId, String sessionId, Long workspaceId, Long userId, String query) {
    this.traceId = traceId;
    this.sessionId = sessionId;
    this.workspaceId = workspaceId;
    this.userId = userId;
    this.query = query;
    this.startMs = System.currentTimeMillis();
  }

  public void recordCandidate(CandidateTraceItem item) {
    if (item != null) {
      candidates.add(item);
    }
  }

  public void recordSessionId(String sessionId) {
    if (StrUtil.isNotBlank(sessionId)) {
      this.sessionId = sessionId.trim();
    }
  }

  public void recordEmbeddingTime(long ms) {
    this.embeddingMs.set(ms);
  }

  public void recordRetrievalTime(long ms) {
    this.retrievalMs.set(ms);
  }

  public void recordRerankTime(long ms) {
    this.rerankMs.set(ms);
  }

  public void recordGenerationTime(long ms) {
    this.generationMs.set(ms);
  }

  public void recordCitations(List<CitationVO> citations) {
    this.citations = (citations != null) ? List.copyOf(citations) : List.of();
  }

  public void recordAnswer(String answer, String modelName) {
    this.answerContent = answer;
    if (StrUtil.isNotBlank(modelName)) {
      this.modelName = modelName.trim();
    }
  }

  public void recordModelName(String modelName) {
    if (StrUtil.isNotBlank(modelName)) {
      this.modelName = modelName.trim();
    }
  }

  public void recordRerankStrategy(String strategy) {
    this.rerankStrategy = strategy;
  }

  public void recordEmbeddingTokens(long tokens) {
    if (tokens > 0) {
      this.embeddingTokens.addAndGet(tokens);
    }
  }

  public void recordRerankTokens(long tokens) {
    if (tokens > 0) {
      this.rerankTokens.addAndGet(tokens);
    }
  }

  public void recordLlmTokens(Long promptTokens, Long completionTokens) {
    if (promptTokens != null && promptTokens > 0) {
      this.promptTokens.set(promptTokens);
    }
    if (completionTokens != null && completionTokens > 0) {
      this.completionTokens.set(completionTokens);
    }
  }

  public void recordTokenUsage(Integer promptTokens, Integer completionTokens) {
    recordLlmTokens(
        promptTokens != null ? promptTokens.longValue() : null,
        completionTokens != null ? completionTokens.longValue() : null);
  }

  public int getEmbeddingTokens() {
    return (int) embeddingTokens.get();
  }

  public int getRerankTokens() {
    return (int) rerankTokens.get();
  }

  public int getPromptTokens() {
    return (int) promptTokens.get();
  }

  public int getCompletionTokens() {
    return (int) completionTokens.get();
  }

  public int getTotalTokens() {
    return (int)
        (embeddingTokens.get() + rerankTokens.get() + promptTokens.get() + completionTokens.get());
  }

  public void recordCallSource(String source) {
    if (source != null && !source.isBlank()) {
      this.callSource =
          io.lexiloom.application.enums.AiCallSourceEnum.from(
                  source, io.lexiloom.application.enums.AiCallSourceEnum.OPENAPI)
              .getCode();
    }
  }

  public String getCallSource() {
    return callSource;
  }

  public void recordApiKey(String apiKey) {
    if (apiKey != null && !apiKey.isBlank()) {
      this.apiKey = apiKey.trim();
    }
  }

  public String getApiKey() {
    return apiKey;
  }

  public void markError(Throwable t) {
    if (t != null) {
      this.errorMessage = t.getMessage();
    }
  }

  public TraceLatency buildLatency() {
    long totalMs = System.currentTimeMillis() - startMs;
    return new TraceLatency(
        embeddingMs.get(), retrievalMs.get(), rerankMs.get(), generationMs.get(), totalMs);
  }

  public List<CandidateTraceItem> getCandidates() {
    return Collections.unmodifiableList(candidates);
  }
}
