package io.lexiloom.application.ai.service.impl;

import cn.hutool.core.util.StrUtil;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.StringExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.lexiloom.application.ai.api.AiStatsQueryApi;
import io.lexiloom.application.ai.api.dto.TokenUsageStatsDTO;
import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.mapper.TokenStatsMapper;
import io.lexiloom.application.ai.model.request.ApiKeyTokenUsageQueryRequest;
import io.lexiloom.application.ai.model.response.ApiKeyTokenUsageResponse;
import io.lexiloom.application.ai.model.response.TokenUsageStatsResponse;
import io.lexiloom.application.ai.repository.DocChunkRepository;
import io.lexiloom.application.ai.service.AiStatsQueryService;
import io.lexiloom.application.ai.trace.entity.QRagTraceEntity;
import io.lexiloom.application.utils.DesensitizeUtils;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 智脑状态与统计查询实现
 *
 * <p>同时对外提供 Spring Modulith 跨模块查询接口 ({@link AiStatsQueryApi}) 及模块内控制层服务 ({@link
 * AiStatsQueryService})。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiStatsQueryApiImpl implements AiStatsQueryApi, AiStatsQueryService {

  private final DocChunkRepository docChunkRepository;
  private final AiProperties aiProperties;
  private final JPAQueryFactory queryFactory;
  private final TokenStatsMapper tokenStatsMapper;

  @Override
  public long countChunksByWorkspaceId(Long workspaceId) {
    if (workspaceId == null) {
      return 0L;
    }
    try {
      return docChunkRepository.countByWorkspaceId(String.valueOf(workspaceId));
    } catch (Exception e) {
      log.warn("【AiStatsQueryApi】查询 ES 空间切片数异常: wsId={}, error={}", workspaceId, e.getMessage());
      return 0L;
    }
  }

  @Override
  public boolean isAiEnabled() {
    return aiProperties != null && aiProperties.isEnabled();
  }

  /**
   * 统计指定周期内的 Token 消耗情况（包含按日趋势明细折线图数据、全周期总计及各模型单独总计）
   *
   * @param workspaceId 可选的工作空间 ID (为 null 表示统计全局)
   * @param days 统计天数周期 (若为 null 或 <= 0 则默认 7 天)
   * @return Token 消耗统计数据传输对象
   */
  @Override
  @Transactional(readOnly = true)
  public TokenUsageStatsDTO getTokenUsageStats(Long workspaceId, Integer days) {
    int effectiveDays = (days == null || days <= 0) ? 7 : days;
    LocalDateTime endTime = LocalDateTime.now(ZoneId.systemDefault());
    LocalDateTime startTime =
        LocalDate.now(ZoneId.systemDefault()).minusDays(effectiveDays - 1L).atStartOfDay();

    QRagTraceEntity qTrace = QRagTraceEntity.ragTraceEntity;
    BooleanBuilder builder = new BooleanBuilder();
    builder.and(qTrace.createTime.goe(startTime));
    builder.and(qTrace.createTime.loe(endTime));

    if (workspaceId != null) {
      builder.and(qTrace.workspaceId.eq(workspaceId));
    }

    StringExpression dateExpr =
        Expressions.stringTemplate("TO_CHAR({0}, 'YYYY-MM-DD')", qTrace.createTime);

    List<Tuple> tuples =
        queryFactory
            .select(
                dateExpr,
                qTrace.modelName,
                qTrace.promptTokens.sum().coalesce(0),
                qTrace.completionTokens.sum().coalesce(0),
                qTrace.embeddingTokens.sum().coalesce(0),
                qTrace.rerankTokens.sum().coalesce(0),
                qTrace.totalTokens.sum().coalesce(0),
                qTrace.count())
            .from(qTrace)
            .where(builder)
            .groupBy(dateExpr, qTrace.modelName)
            .orderBy(dateExpr.asc())
            .fetch();

    record ModelStatRecord(
        String date,
        String modelName,
        long promptTokens,
        long completionTokens,
        long embeddingTokens,
        long rerankTokens,
        long totalTokens,
        long callCount) {}

    List<ModelStatRecord> records = new ArrayList<>();
    Set<String> distinctModels = new LinkedHashSet<>();

    if (tuples != null) {
      for (Tuple tuple : tuples) {
        String date = tuple.get(0, String.class);
        String rawModel = tuple.get(1, String.class);
        String modelName = StrUtil.isNotBlank(rawModel) ? rawModel : "UNKNOWN";
        long prompt = getLong(tuple.get(2, Number.class));
        long completion = getLong(tuple.get(3, Number.class));
        long embedding = getLong(tuple.get(4, Number.class));
        long rerank = getLong(tuple.get(5, Number.class));
        long total = getLong(tuple.get(6, Number.class));
        long calls = getLong(tuple.get(7, Number.class));

        if (total == 0L && (prompt > 0L || completion > 0L || embedding > 0L || rerank > 0L)) {
          total = prompt + completion + embedding + rerank;
        }

        records.add(
            new ModelStatRecord(
                date, modelName, prompt, completion, embedding, rerank, total, calls));
        distinctModels.add(modelName);
      }
    }

    // 1. 全周期综合总计与各模型综合统计 (group by model across all dates)
    Map<String, long[]> modelAggregates = new HashMap<>();
    long sumTotalTokens = 0L;
    long sumPromptTokens = 0L;
    long sumCompletionTokens = 0L;
    long sumEmbeddingTokens = 0L;
    long sumRerankTokens = 0L;
    long sumCallCount = 0L;

    for (ModelStatRecord r : records) {
      long[] agg = modelAggregates.computeIfAbsent(r.modelName(), k -> new long[6]);
      agg[0] += r.totalTokens();
      agg[1] += r.promptTokens();
      agg[2] += r.completionTokens();
      agg[3] += r.embeddingTokens();
      agg[4] += r.rerankTokens();
      agg[5] += r.callCount();

      sumTotalTokens += r.totalTokens();
      sumPromptTokens += r.promptTokens();
      sumCompletionTokens += r.completionTokens();
      sumEmbeddingTokens += r.embeddingTokens();
      sumRerankTokens += r.rerankTokens();
      sumCallCount += r.callCount();
    }

    final long overallTotal = sumTotalTokens;
    List<TokenUsageStatsDTO.ModelTokenUsageDTO> overallModelBreakdown =
        modelAggregates.entrySet().stream()
            .map(
                entry -> {
                  String modelName = entry.getKey();
                  long[] a = entry.getValue();
                  double percentage =
                      overallTotal > 0L
                          ? Math.round(((double) a[0] / overallTotal) * 10000.0) / 100.0
                          : 0.0;
                  return new TokenUsageStatsDTO.ModelTokenUsageDTO(
                      modelName, a[0], a[1], a[2], a[3], a[4], a[5], percentage);
                })
            .sorted(
                Comparator.comparingLong(TokenUsageStatsDTO.ModelTokenUsageDTO::totalTokens)
                    .reversed())
            .toList();

    TokenUsageStatsDTO.TokenUsageSummaryDTO totalSummary =
        new TokenUsageStatsDTO.TokenUsageSummaryDTO(
            sumTotalTokens,
            sumPromptTokens,
            sumCompletionTokens,
            sumEmbeddingTokens,
            sumRerankTokens,
            sumCallCount);

    // 2. 按天归类映射 (date -> modelName -> ModelStatRecord)
    Map<String, Map<String, ModelStatRecord>> dateModelMap = new HashMap<>();
    for (ModelStatRecord r : records) {
      if (r.date() != null) {
        dateModelMap.computeIfAbsent(r.date(), k -> new HashMap<>()).put(r.modelName(), r);
      }
    }

    // 统一各模型在折线图中的排序顺序（按总消耗降序）
    List<String> sortedModelNames =
        overallModelBreakdown.stream()
            .map(TokenUsageStatsDTO.ModelTokenUsageDTO::modelName)
            .toList();

    // 3. 构建连续 N 天按日折线图序列（缺失日期自动 0 填充，保障 X 轴时间连续性）
    List<TokenUsageStatsDTO.DailyTokenUsageDTO> dailyStats = new ArrayList<>(effectiveDays);
    for (int i = effectiveDays - 1; i >= 0; i--) {
      String dateStr = LocalDate.now().minusDays(i).toString();
      Map<String, ModelStatRecord> dayModelData = dateModelMap.getOrDefault(dateStr, Map.of());

      long dayTotalTokens = 0L;
      long dayPromptTokens = 0L;
      long dayCompletionTokens = 0L;
      long dayEmbeddingTokens = 0L;
      long dayRerankTokens = 0L;
      long dayCallCount = 0L;

      List<TokenUsageStatsDTO.DailyModelTokenDTO> dayModels = new ArrayList<>();
      for (String modelName : sortedModelNames) {
        ModelStatRecord stat = dayModelData.get(modelName);
        if (stat != null) {
          dayTotalTokens += stat.totalTokens();
          dayPromptTokens += stat.promptTokens();
          dayCompletionTokens += stat.completionTokens();
          dayEmbeddingTokens += stat.embeddingTokens();
          dayRerankTokens += stat.rerankTokens();
          dayCallCount += stat.callCount();

          dayModels.add(
              new TokenUsageStatsDTO.DailyModelTokenDTO(
                  modelName,
                  stat.totalTokens(),
                  stat.promptTokens(),
                  stat.completionTokens(),
                  stat.embeddingTokens(),
                  stat.rerankTokens(),
                  stat.callCount()));
        } else {
          dayModels.add(
              new TokenUsageStatsDTO.DailyModelTokenDTO(modelName, 0L, 0L, 0L, 0L, 0L, 0L));
        }
      }

      dailyStats.add(
          new TokenUsageStatsDTO.DailyTokenUsageDTO(
              dateStr,
              dayTotalTokens,
              dayPromptTokens,
              dayCompletionTokens,
              dayEmbeddingTokens,
              dayRerankTokens,
              dayCallCount,
              dayModels));
    }

    return new TokenUsageStatsDTO(
        effectiveDays,
        startTime,
        endTime,
        workspaceId,
        totalSummary,
        overallModelBreakdown,
        dailyStats);
  }

  /**
   * 统计指定周期内的 Token 消耗并转换为对外响应视图对象
   *
   * @param workspaceId 可选的工作空间 ID
   * @param days 统计天数周期 (若为 null 或 <= 0 则默认 7 天)
   * @return Token 消耗统计对外响应对象 (含按日折线图序列)
   */
  @Override
  @Transactional(readOnly = true)
  public TokenUsageStatsResponse getTokenUsageStatsResponse(Long workspaceId, Integer days) {
    TokenUsageStatsDTO dto = getTokenUsageStats(workspaceId, days);
    return tokenStatsMapper.toResponse(dto);
  }

  /**
   * 统计每个或指定 API Key 的 Token 消耗用量趋势（支持按日 DAILY 或按月 MONTHLY）
   *
   * @param request 查询请求参数 (含可选 apiKey, period=DAILY/MONTHLY, days, months, workspaceId)
   * @return API Key Token 消耗趋势响应对象
   */
  @Override
  @Transactional(readOnly = true)
  public ApiKeyTokenUsageResponse getApiKeyTokenUsageStats(ApiKeyTokenUsageQueryRequest request) {
    ApiKeyTokenUsageQueryRequest req =
        request != null ? request : new ApiKeyTokenUsageQueryRequest(null, null, null, null, null);

    boolean isMonthly = req.isMonthly();
    int effectiveRange = req.getEffectiveRange();
    ZoneId zone = ZoneId.systemDefault();
    LocalDateTime endTime = LocalDateTime.now(zone);

    LocalDateTime startTime;
    List<String> periodKeys = new ArrayList<>(effectiveRange);

    if (isMonthly) {
      YearMonth currentMonth = YearMonth.now(zone);
      YearMonth startMonth = currentMonth.minusMonths(effectiveRange - 1L);
      startTime = startMonth.atDay(1).atStartOfDay();
      for (int i = 0; i < effectiveRange; i++) {
        periodKeys.add(startMonth.plusMonths(i).toString()); // "yyyy-MM"
      }
    } else {
      LocalDate today = LocalDate.now(zone);
      LocalDate startDate = today.minusDays(effectiveRange - 1L);
      startTime = startDate.atStartOfDay();
      for (int i = 0; i < effectiveRange; i++) {
        periodKeys.add(startDate.plusDays(i).toString()); // "yyyy-MM-dd"
      }
    }

    QRagTraceEntity qTrace = QRagTraceEntity.ragTraceEntity;
    BooleanBuilder builder = new BooleanBuilder();
    builder.and(qTrace.createTime.goe(startTime));
    builder.and(qTrace.createTime.loe(endTime));

    String targetMaskedKey = null;
    if (StrUtil.isNotBlank(req.apiKey())) {
      targetMaskedKey = DesensitizeUtils.maskApiKey(req.apiKey().trim());
      builder.and(qTrace.apiKey.eq(targetMaskedKey));
    } else {
      builder.and(qTrace.apiKey.isNotNull()).and(qTrace.apiKey.ne(""));
    }

    if (req.workspaceId() != null) {
      builder.and(qTrace.workspaceId.eq(req.workspaceId()));
    }

    String pattern = isMonthly ? "YYYY-MM" : "YYYY-MM-DD";
    StringExpression dateExpr =
        Expressions.stringTemplate("TO_CHAR({0}, '" + pattern + "')", qTrace.createTime);

    List<Tuple> tuples =
        queryFactory
            .select(
                dateExpr,
                qTrace.apiKey,
                qTrace.promptTokens.sum().coalesce(0),
                qTrace.completionTokens.sum().coalesce(0),
                qTrace.embeddingTokens.sum().coalesce(0),
                qTrace.rerankTokens.sum().coalesce(0),
                qTrace.totalTokens.sum().coalesce(0),
                qTrace.count())
            .from(qTrace)
            .where(builder)
            .groupBy(dateExpr, qTrace.apiKey)
            .orderBy(dateExpr.asc())
            .fetch();

    record KeyStatRecord(
        String period,
        String apiKey,
        long promptTokens,
        long completionTokens,
        long embeddingTokens,
        long rerankTokens,
        long totalTokens,
        long callCount) {}

    List<KeyStatRecord> records = new ArrayList<>();
    Map<String, long[]> keyAggregates = new HashMap<>(); // apiKey -> [total, prompt, completion, calls]

    long grandTotalTokens = 0L;
    long grandPromptTokens = 0L;
    long grandCompletionTokens = 0L;
    long grandEmbeddingTokens = 0L;
    long grandRerankTokens = 0L;
    long grandCallCount = 0L;

    if (tuples != null) {
      for (Tuple tuple : tuples) {
        String period = tuple.get(0, String.class);
        String apiKey = tuple.get(1, String.class);
        if (StrUtil.isBlank(period) || StrUtil.isBlank(apiKey)) {
          continue;
        }

        long prompt = getLong(tuple.get(2, Number.class));
        long completion = getLong(tuple.get(3, Number.class));
        long embedding = getLong(tuple.get(4, Number.class));
        long rerank = getLong(tuple.get(5, Number.class));
        long total = getLong(tuple.get(6, Number.class));
        long calls = getLong(tuple.get(7, Number.class));

        if (total == 0L && (prompt > 0L || completion > 0L || embedding > 0L || rerank > 0L)) {
          total = prompt + completion + embedding + rerank;
        }

        records.add(
            new KeyStatRecord(period, apiKey, prompt, completion, embedding, rerank, total, calls));

        grandTotalTokens += total;
        grandPromptTokens += prompt;
        grandCompletionTokens += completion;
        grandEmbeddingTokens += embedding;
        grandRerankTokens += rerank;
        grandCallCount += calls;

        long[] keyAgg = keyAggregates.computeIfAbsent(apiKey, k -> new long[4]);
        keyAgg[0] += total;
        keyAgg[1] += prompt;
        keyAgg[2] += completion;
        keyAgg[3] += calls;
      }
    }

    ApiKeyTokenUsageResponse.TokenUsageSummaryVO summary =
        new ApiKeyTokenUsageResponse.TokenUsageSummaryVO(
            grandTotalTokens,
            grandPromptTokens,
            grandCompletionTokens,
            grandEmbeddingTokens,
            grandRerankTokens,
            grandCallCount);

    // 计算各 API Key 在全周期内的用量排行
    List<ApiKeyTokenUsageResponse.KeyBreakdownVO> keyBreakdown = new ArrayList<>();
    final long totalForCalc = grandTotalTokens;
    for (Map.Entry<String, long[]> entry : keyAggregates.entrySet()) {
      String key = entry.getKey();
      long[] agg = entry.getValue();
      double pct = totalForCalc > 0 ? (agg[0] * 100.0 / totalForCalc) : 0.0;
      double roundedPct = Math.round(pct * 100.0) / 100.0;
      keyBreakdown.add(
          new ApiKeyTokenUsageResponse.KeyBreakdownVO(
              key, agg[0], agg[1], agg[2], agg[3], roundedPct));
    }
    keyBreakdown.sort(
        Comparator.comparingLong(ApiKeyTokenUsageResponse.KeyBreakdownVO::totalTokens).reversed());

    // 构建时间序列 timeline，按日期/月份连续补 0
    Map<String, List<KeyStatRecord>> recordsByPeriod = new HashMap<>();
    for (KeyStatRecord rec : records) {
      recordsByPeriod.computeIfAbsent(rec.period(), k -> new ArrayList<>()).add(rec);
    }

    List<ApiKeyTokenUsageResponse.TimelineTokenUsageVO> timeline =
        new ArrayList<>(periodKeys.size());
    for (String period : periodKeys) {
      List<KeyStatRecord> periodRecords = recordsByPeriod.getOrDefault(period, List.of());
      long pTotal = 0L;
      long pPrompt = 0L;
      long pCompletion = 0L;
      long pEmbedding = 0L;
      long pRerank = 0L;
      long pCalls = 0L;
      List<ApiKeyTokenUsageResponse.KeyDetailVO> keyDetails =
          new ArrayList<>(periodRecords.size());

      for (KeyStatRecord r : periodRecords) {
        pTotal += r.totalTokens();
        pPrompt += r.promptTokens();
        pCompletion += r.completionTokens();
        pEmbedding += r.embeddingTokens();
        pRerank += r.rerankTokens();
        pCalls += r.callCount();
        keyDetails.add(
            new ApiKeyTokenUsageResponse.KeyDetailVO(
                r.apiKey(), r.totalTokens(), r.promptTokens(), r.completionTokens(), r.callCount()));
      }
      keyDetails.sort(
          Comparator.comparingLong(ApiKeyTokenUsageResponse.KeyDetailVO::totalTokens).reversed());

      timeline.add(
          new ApiKeyTokenUsageResponse.TimelineTokenUsageVO(
              period, pTotal, pPrompt, pCompletion, pEmbedding, pRerank, pCalls, keyDetails));
    }

    return new ApiKeyTokenUsageResponse(
        isMonthly ? "MONTHLY" : "DAILY",
        targetMaskedKey,
        req.workspaceId(),
        startTime,
        endTime,
        summary,
        keyBreakdown,
        timeline);
  }

  private long getLong(Number number) {
    return number != null ? number.longValue() : 0L;
  }
}
