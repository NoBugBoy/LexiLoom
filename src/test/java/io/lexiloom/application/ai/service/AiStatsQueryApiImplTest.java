package io.lexiloom.application.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Predicate;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.lexiloom.application.ai.api.dto.TokenUsageStatsDTO;
import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.mapper.TokenStatsMapper;
import io.lexiloom.application.ai.mapper.TokenStatsMapperImpl;
import io.lexiloom.application.ai.model.request.ApiKeyTokenUsageQueryRequest;
import io.lexiloom.application.ai.model.response.ApiKeyTokenUsageResponse;
import io.lexiloom.application.ai.model.response.TokenUsageStatsResponse;
import io.lexiloom.application.ai.repository.DocChunkRepository;
import io.lexiloom.application.ai.service.impl.AiStatsQueryApiImpl;
import io.lexiloom.application.ai.trace.entity.QRagTraceEntity;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiStatsQueryApiImplTest {

  @Mock private DocChunkRepository docChunkRepository;
  @Mock private JPAQueryFactory queryFactory;
  @Mock private JPAQuery<Tuple> jpaQuery;

  private AiProperties aiProperties;
  private TokenStatsMapper tokenStatsMapper;
  private AiStatsQueryApiImpl service;

  @BeforeEach
  void setUp() {
    aiProperties = new AiProperties();
    aiProperties.setEnabled(true);
    tokenStatsMapper = new TokenStatsMapperImpl();
    service =
        new AiStatsQueryApiImpl(docChunkRepository, aiProperties, queryFactory, tokenStatsMapper);
  }

  @Test
  @DisplayName("验证 countChunksByWorkspaceId 统计切片数")
  void testCountChunks() {
    when(docChunkRepository.countByWorkspaceId("100")).thenReturn(42L);
    assertThat(service.countChunksByWorkspaceId(100L)).isEqualTo(42L);
    assertThat(service.countChunksByWorkspaceId(null)).isEqualTo(0L);
  }

  @Test
  @DisplayName("验证 isAiEnabled 状态读取")
  void testIsAiEnabled() {
    assertThat(service.isAiEnabled()).isTrue();
    aiProperties.setEnabled(false);
    assertThat(service.isAiEnabled()).isFalse();
  }

  @Test
  @DisplayName("验证 getTokenUsageStats 计算最近7天按日折线图数据与全周期模型汇总")
  @SuppressWarnings("unchecked")
  void testGetTokenUsageStats() {
    String todayStr = LocalDate.now().toString();
    String twoDaysAgoStr = LocalDate.now().minusDays(2).toString();

    Tuple tuple1 = mock(Tuple.class);
    when(tuple1.get(0, String.class)).thenReturn(twoDaysAgoStr);
    when(tuple1.get(1, String.class)).thenReturn("qwen-plus");
    when(tuple1.get(2, Number.class)).thenReturn(1000);
    when(tuple1.get(3, Number.class)).thenReturn(2000);
    when(tuple1.get(4, Number.class)).thenReturn(500);
    when(tuple1.get(5, Number.class)).thenReturn(100);
    when(tuple1.get(6, Number.class)).thenReturn(3600);
    when(tuple1.get(7, Number.class)).thenReturn(5L);

    Tuple tuple2 = mock(Tuple.class);
    when(tuple2.get(0, String.class)).thenReturn(todayStr);
    when(tuple2.get(1, String.class)).thenReturn("gpt-4o");
    when(tuple2.get(2, Number.class)).thenReturn(2000);
    when(tuple2.get(3, Number.class)).thenReturn(4000);
    when(tuple2.get(4, Number.class)).thenReturn(0);
    when(tuple2.get(5, Number.class)).thenReturn(0);
    when(tuple2.get(6, Number.class)).thenReturn(6000);
    when(tuple2.get(7, Number.class)).thenReturn(3L);

    QRagTraceEntity qTrace = QRagTraceEntity.ragTraceEntity;
    when(queryFactory.select(
            any(Expression.class),
            any(Expression.class),
            any(Expression.class),
            any(Expression.class),
            any(Expression.class),
            any(Expression.class),
            any(Expression.class),
            any(Expression.class)))
        .thenReturn(jpaQuery);
    when(jpaQuery.from(qTrace)).thenReturn(jpaQuery);
    when(jpaQuery.where(any(Predicate.class))).thenReturn(jpaQuery);
    when(jpaQuery.groupBy(any(Expression.class), any(Expression.class))).thenReturn(jpaQuery);
    when(jpaQuery.orderBy(any(OrderSpecifier.class))).thenReturn(jpaQuery);
    when(jpaQuery.fetch()).thenReturn(List.of(tuple1, tuple2));

    TokenUsageStatsDTO dto = service.getTokenUsageStats(1L, 7);

    assertThat(dto).isNotNull();
    assertThat(dto.days()).isEqualTo(7);
    assertThat(dto.workspaceId()).isEqualTo(1L);

    // 1. 校验全周期综合总计
    TokenUsageStatsDTO.TokenUsageSummaryDTO total = dto.total();
    assertThat(total.totalTokens()).isEqualTo(9600L);
    assertThat(total.promptTokens()).isEqualTo(3000L);
    assertThat(total.completionTokens()).isEqualTo(6000L);
    assertThat(total.embeddingTokens()).isEqualTo(500L);
    assertThat(total.rerankTokens()).isEqualTo(100L);
    assertThat(total.callCount()).isEqualTo(8L);

    // 2. 校验各模型全周期总计 (按消耗降序排序)
    List<TokenUsageStatsDTO.ModelTokenUsageDTO> models = dto.modelBreakdown();
    assertThat(models).hasSize(2);

    TokenUsageStatsDTO.ModelTokenUsageDTO firstModel = models.get(0);
    assertThat(firstModel.modelName()).isEqualTo("gpt-4o");
    assertThat(firstModel.totalTokens()).isEqualTo(6000L);
    assertThat(firstModel.percentage()).isEqualTo(62.5);

    TokenUsageStatsDTO.ModelTokenUsageDTO secondModel = models.get(1);
    assertThat(secondModel.modelName()).isEqualTo("qwen-plus");
    assertThat(secondModel.totalTokens()).isEqualTo(3600L);
    assertThat(secondModel.percentage()).isEqualTo(37.5);

    // 3. 校验最近 7 天按日折线图序列 (保障连续 7 天，零填充)
    List<TokenUsageStatsDTO.DailyTokenUsageDTO> dailyStats = dto.dailyStats();
    assertThat(dailyStats).hasSize(7);

    // 校验最后一天 (今天) 的折线图节点
    TokenUsageStatsDTO.DailyTokenUsageDTO todayStat = dailyStats.get(6);
    assertThat(todayStat.date()).isEqualTo(todayStr);
    assertThat(todayStat.totalTokens()).isEqualTo(6000L);
    assertThat(todayStat.promptTokens()).isEqualTo(2000L);
    assertThat(todayStat.completionTokens()).isEqualTo(4000L);
    assertThat(todayStat.callCount()).isEqualTo(3L);
    // 包含所有 active 模型的每日分布 (无流量模型自动 0 填充)
    assertThat(todayStat.modelBreakdown()).hasSize(2);
    assertThat(todayStat.modelBreakdown().get(0).modelName()).isEqualTo("gpt-4o");
    assertThat(todayStat.modelBreakdown().get(0).totalTokens()).isEqualTo(6000L);
    assertThat(todayStat.modelBreakdown().get(1).modelName()).isEqualTo("qwen-plus");
    assertThat(todayStat.modelBreakdown().get(1).totalTokens()).isZero();

    // 校验倒数第三天 (twoDaysAgo)
    TokenUsageStatsDTO.DailyTokenUsageDTO twoDaysAgoStat = dailyStats.get(4);
    assertThat(twoDaysAgoStat.date()).isEqualTo(twoDaysAgoStr);
    assertThat(twoDaysAgoStat.totalTokens()).isEqualTo(3600L);
    assertThat(twoDaysAgoStat.callCount()).isEqualTo(5L);

    // 校验无任何问答的空日 (如昨天)
    TokenUsageStatsDTO.DailyTokenUsageDTO yesterdayStat = dailyStats.get(5);
    assertThat(yesterdayStat.totalTokens()).isZero();
    assertThat(yesterdayStat.callCount()).isZero();
  }

  @Test
  @DisplayName("验证 getTokenUsageStats 在数据为空时的兜底处理（完整 7 天 0 填充折线图）")
  @SuppressWarnings("unchecked")
  void testGetTokenUsageStatsEmpty() {
    QRagTraceEntity qTrace = QRagTraceEntity.ragTraceEntity;
    when(queryFactory.select(
            any(Expression.class),
            any(Expression.class),
            any(Expression.class),
            any(Expression.class),
            any(Expression.class),
            any(Expression.class),
            any(Expression.class),
            any(Expression.class)))
        .thenReturn(jpaQuery);
    when(jpaQuery.from(qTrace)).thenReturn(jpaQuery);
    when(jpaQuery.where(any(Predicate.class))).thenReturn(jpaQuery);
    when(jpaQuery.groupBy(any(Expression.class), any(Expression.class))).thenReturn(jpaQuery);
    when(jpaQuery.orderBy(any(OrderSpecifier.class))).thenReturn(jpaQuery);
    when(jpaQuery.fetch()).thenReturn(List.of());

    TokenUsageStatsDTO dto = service.getTokenUsageStats(null, null);

    assertThat(dto).isNotNull();
    assertThat(dto.days()).isEqualTo(7);
    assertThat(dto.workspaceId()).isNull();
    assertThat(dto.total().totalTokens()).isZero();
    assertThat(dto.total().callCount()).isZero();
    assertThat(dto.modelBreakdown()).isEmpty();

    // 折线图必须始终拥有完整的 7 天节点
    assertThat(dto.dailyStats()).hasSize(7);
    for (TokenUsageStatsDTO.DailyTokenUsageDTO day : dto.dailyStats()) {
      assertThat(day.totalTokens()).isZero();
      assertThat(day.callCount()).isZero();
    }
  }

  @Test
  @DisplayName("验证 getTokenUsageStatsResponse 对外响应视图转换包含折线图明细")
  @SuppressWarnings("unchecked")
  void testGetTokenUsageStatsResponse() {
    String todayStr = LocalDate.now().toString();
    Tuple tuple = mock(Tuple.class);
    when(tuple.get(0, String.class)).thenReturn(todayStr);
    when(tuple.get(1, String.class)).thenReturn(""); // 空模型名称转为 UNKNOWN
    when(tuple.get(2, Number.class)).thenReturn(100);
    when(tuple.get(3, Number.class)).thenReturn(200);
    when(tuple.get(4, Number.class)).thenReturn(0);
    when(tuple.get(5, Number.class)).thenReturn(0);
    when(tuple.get(6, Number.class)).thenReturn(300);
    when(tuple.get(7, Number.class)).thenReturn(1L);

    QRagTraceEntity qTrace = QRagTraceEntity.ragTraceEntity;
    when(queryFactory.select(
            any(Expression.class),
            any(Expression.class),
            any(Expression.class),
            any(Expression.class),
            any(Expression.class),
            any(Expression.class),
            any(Expression.class),
            any(Expression.class)))
        .thenReturn(jpaQuery);
    when(jpaQuery.from(qTrace)).thenReturn(jpaQuery);
    when(jpaQuery.where(any(Predicate.class))).thenReturn(jpaQuery);
    when(jpaQuery.groupBy(any(Expression.class), any(Expression.class))).thenReturn(jpaQuery);
    when(jpaQuery.orderBy(any(OrderSpecifier.class))).thenReturn(jpaQuery);
    when(jpaQuery.fetch()).thenReturn(List.of(tuple));

    TokenUsageStatsResponse response = service.getTokenUsageStatsResponse(10L, 7);

    assertThat(response).isNotNull();
    assertThat(response.days()).isEqualTo(7);
    assertThat(response.total().totalTokens()).isEqualTo(300L);
    assertThat(response.modelBreakdown()).hasSize(1);
    assertThat(response.modelBreakdown().get(0).modelName()).isEqualTo("UNKNOWN");
    assertThat(response.modelBreakdown().get(0).percentage()).isEqualTo(100.0);
    assertThat(response.dailyStats()).hasSize(7);
    assertThat(response.dailyStats().get(6).totalTokens()).isEqualTo(300L);
  }

  @Test
  @DisplayName("验证 getApiKeyTokenUsageStats 按日统计指定 API Key 消耗趋势")
  @SuppressWarnings("unchecked")
  void testGetApiKeyTokenUsageStatsDaily() {
    String todayStr = LocalDate.now().toString();
    Tuple tuple = mock(Tuple.class);
    when(tuple.get(0, String.class)).thenReturn(todayStr);
    when(tuple.get(1, String.class)).thenReturn("lx_live_b7c2****c1be");
    when(tuple.get(2, Number.class)).thenReturn(500);
    when(tuple.get(3, Number.class)).thenReturn(1000);
    when(tuple.get(4, Number.class)).thenReturn(100);
    when(tuple.get(5, Number.class)).thenReturn(50);
    when(tuple.get(6, Number.class)).thenReturn(1650);
    when(tuple.get(7, Number.class)).thenReturn(2L);

    QRagTraceEntity qTrace = QRagTraceEntity.ragTraceEntity;
    when(queryFactory.select(
            any(Expression.class),
            any(Expression.class),
            any(Expression.class),
            any(Expression.class),
            any(Expression.class),
            any(Expression.class),
            any(Expression.class),
            any(Expression.class)))
        .thenReturn(jpaQuery);
    when(jpaQuery.from(qTrace)).thenReturn(jpaQuery);
    when(jpaQuery.where(any(Predicate.class))).thenReturn(jpaQuery);
    when(jpaQuery.groupBy(any(Expression.class), any(Expression.class))).thenReturn(jpaQuery);
    when(jpaQuery.orderBy(any(OrderSpecifier.class))).thenReturn(jpaQuery);
    when(jpaQuery.fetch()).thenReturn(List.of(tuple));

    ApiKeyTokenUsageQueryRequest request =
        new ApiKeyTokenUsageQueryRequest("lx_live_b7c2xxxxxxxxc1be", 1L, "DAILY", 7, null);
    ApiKeyTokenUsageResponse response = service.getApiKeyTokenUsageStats(request);

    assertThat(response).isNotNull();
    assertThat(response.periodType()).isEqualTo("DAILY");
    assertThat(response.targetApiKey()).isEqualTo("lx_live_b7c2****c1be");
    assertThat(response.workspaceId()).isEqualTo(1L);

    // 校验汇总
    assertThat(response.total().totalTokens()).isEqualTo(1650L);
    assertThat(response.total().promptTokens()).isEqualTo(500L);
    assertThat(response.total().completionTokens()).isEqualTo(1000L);
    assertThat(response.total().callCount()).isEqualTo(2L);

    // 校验各 Key 排行
    assertThat(response.keyBreakdown()).hasSize(1);
    assertThat(response.keyBreakdown().getFirst().apiKey()).isEqualTo("lx_live_b7c2****c1be");
    assertThat(response.keyBreakdown().getFirst().percentage()).isEqualTo(100.0);

    // 校验连续 7 天时间序列
    assertThat(response.timeline()).hasSize(7);
    ApiKeyTokenUsageResponse.TimelineTokenUsageVO lastDay = response.timeline().get(6);
    assertThat(lastDay.period()).isEqualTo(todayStr);
    assertThat(lastDay.totalTokens()).isEqualTo(1650L);
    assertThat(lastDay.keyDetails()).hasSize(1);
  }

  @Test
  @DisplayName("验证 getApiKeyTokenUsageStats 按月统计多 Key 并支持空数据补 0")
  @SuppressWarnings("unchecked")
  void testGetApiKeyTokenUsageStatsMonthlyEmpty() {
    QRagTraceEntity qTrace = QRagTraceEntity.ragTraceEntity;
    when(queryFactory.select(
            any(Expression.class),
            any(Expression.class),
            any(Expression.class),
            any(Expression.class),
            any(Expression.class),
            any(Expression.class),
            any(Expression.class),
            any(Expression.class)))
        .thenReturn(jpaQuery);
    when(jpaQuery.from(qTrace)).thenReturn(jpaQuery);
    when(jpaQuery.where(any(Predicate.class))).thenReturn(jpaQuery);
    when(jpaQuery.groupBy(any(Expression.class), any(Expression.class))).thenReturn(jpaQuery);
    when(jpaQuery.orderBy(any(OrderSpecifier.class))).thenReturn(jpaQuery);
    when(jpaQuery.fetch()).thenReturn(List.of());

    ApiKeyTokenUsageQueryRequest request =
        new ApiKeyTokenUsageQueryRequest(null, null, "MONTHLY", null, 6);
    ApiKeyTokenUsageResponse response = service.getApiKeyTokenUsageStats(request);

    assertThat(response).isNotNull();
    assertThat(response.periodType()).isEqualTo("MONTHLY");
    assertThat(response.targetApiKey()).isNull();
    assertThat(response.total().totalTokens()).isZero();
    assertThat(response.keyBreakdown()).isEmpty();

    // 校验连续 6 个月时间序列，全为 0
    assertThat(response.timeline()).hasSize(6);
    String currentMonthStr = YearMonth.now().toString();
    assertThat(response.timeline().get(5).period()).isEqualTo(currentMonthStr);
    assertThat(response.timeline().get(5).totalTokens()).isZero();
    assertThat(response.timeline().get(5).keyDetails()).isEmpty();
  }
}
