package io.lexiloom.application.ai.trace;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.querydsl.core.BooleanBuilder;
import io.lexiloom.application.ai.trace.entity.QRagTraceDetailEntity;
import io.lexiloom.application.ai.trace.entity.QRagTraceEntity;
import io.lexiloom.application.ai.trace.entity.RagTraceDetailEntity;
import io.lexiloom.application.ai.trace.entity.RagTraceEntity;
import io.lexiloom.application.ai.trace.model.RagFeedbackRequest;
import io.lexiloom.application.ai.trace.model.RagTraceContext;
import io.lexiloom.application.ai.trace.model.RagTracePageRequest;
import io.lexiloom.application.ai.trace.repository.RagTraceDetailRepository;
import io.lexiloom.application.ai.trace.repository.RagTraceRepository;
import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.enums.AiCallSourceEnum;
import io.lexiloom.application.exception.BusinessException;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * RAG 全链路观测数据持久化与反馈闭环服务 (RagTracePersistenceService)
 *
 * <p>核心职责：
 *
 * <ul>
 *   <li>由虚拟线程 (Virtual Thread) 异步调用，无阻塞落库观测主表与切片快照明细表；
 *   <li>管理用户端对回答的点赞/点踩与标签反馈，沉淀 RAG 调优数据飞轮。
 * </ul>
 */
@Slf4j
@Service
public class RagTracePersistenceService {

  private final RagTraceRepository ragTraceRepository;
  private final RagTraceDetailRepository ragTraceDetailRepository;
  private final ObjectMapper objectMapper;
  @Nullable private final TransactionTemplate transactionTemplate;

  public RagTracePersistenceService(
      RagTraceRepository ragTraceRepository,
      RagTraceDetailRepository ragTraceDetailRepository,
      ObjectMapper objectMapper) {
    this(ragTraceRepository, ragTraceDetailRepository, objectMapper, null);
  }

  @Autowired
  public RagTracePersistenceService(
      RagTraceRepository ragTraceRepository,
      RagTraceDetailRepository ragTraceDetailRepository,
      ObjectMapper objectMapper,
      @Nullable TransactionTemplate transactionTemplate) {
    this.ragTraceRepository = ragTraceRepository;
    this.ragTraceDetailRepository = ragTraceDetailRepository;
    this.objectMapper = objectMapper;
    this.transactionTemplate = transactionTemplate;
  }

  /**
   * 使用独立 Virtual Thread 异步持久化观测数据 (零线程池、零阻塞业务链路)
   *
   * @param context 运行期全链路上下文对象
   */
  public void asyncSaveTrace(RagTraceContext context) {
    if (context == null || StrUtil.isBlank(context.getTraceId())) {
      return;
    }
    Thread.ofVirtual()
        .name("rag-trace-save-" + context.getTraceId())
        .start(
            () -> {
              try {
                if (transactionTemplate != null) {
                  transactionTemplate.executeWithoutResult(status -> saveTraceInternal(context));
                } else {
                  saveTraceInternal(context);
                }
              } catch (Exception e) {
                log.error(
                    "【RagTrace】虚拟线程异步保存链路快照异常: traceId={}, error={}",
                    context.getTraceId(),
                    e.getMessage(),
                    e);
              }
            });
  }

  /** 写入观测主表及明细表事务方法 */
  @Transactional
  public void saveTrace(RagTraceContext context) {
    saveTraceInternal(context);
  }

  private void saveTraceInternal(RagTraceContext context) {
    Objects.requireNonNull(context, "traceContext 不能为空");
    QRagTraceEntity qTrace = QRagTraceEntity.ragTraceEntity;
    if (ragTraceRepository.exists(qTrace.traceId.eq(context.getTraceId()))) {
      log.debug("【RagTrace】链路记录已存在，跳过重复写入: traceId={}", context.getTraceId());
      return;
    }

    // 1. 委派充血领域工厂构建主表记录
    RagTraceEntity traceEntity = RagTraceEntity.fromContext(context);
    ragTraceRepository.save(traceEntity);

    // 2. 序列化全量快照大字段写入明细表
    String candidateSnapshotJson = toJsonSafe(context.getCandidates());
    String citationsJson = toJsonSafe(context.getCitations());

    RagTraceDetailEntity detailEntity =
        RagTraceDetailEntity.create(
            context.getTraceId(), candidateSnapshotJson, citationsJson, context.getAnswerContent());

    ragTraceDetailRepository.save(detailEntity);

    log.info(
        "【RagTrace】链路观测快照持久化成功: traceId={}, wsId={}, 初筛候选={}, 最终采纳={}, 总耗时={}ms,"
            + " 真实Token消耗=[prompt={}, completion={}, embedding={}, rerank={}, total={}]",
        context.getTraceId(),
        context.getWorkspaceId(),
        traceEntity.getTotalCandidates(),
        traceEntity.getRetainedChunks(),
        traceEntity.getTotalTimeMs(),
        context.getPromptTokens(),
        context.getCompletionTokens(),
        context.getEmbeddingTokens(),
        context.getRerankTokens(),
        context.getTotalTokens());
  }

  /**
   * 记录用户反馈 (点赞/点踩与标签)
   *
   * @param traceId 链路 ID
   * @param request 反馈内容
   * @param userId 操作用户
   */
  @Transactional
  public void recordFeedback(String traceId, RagFeedbackRequest request, Long userId) {
    if (StrUtil.isBlank(traceId) || request == null || request.rating() == null) {
      throw BusinessException.badRequest("反馈参数不完整");
    }

    QRagTraceEntity qTrace = QRagTraceEntity.ragTraceEntity;
    RagTraceEntity entity =
        ragTraceRepository
            .findOne(qTrace.traceId.eq(traceId))
            .orElseThrow(() -> BusinessException.notFound("未找到对应的链路记录: " + traceId));

    String tags = CollUtil.isNotEmpty(request.tags()) ? String.join(",", request.tags()) : null;
    entity.recordFeedback(request.rating(), tags, request.comment());
    ragTraceRepository.save(entity);

    log.info("【RagTrace】成功记录用户反馈: traceId={}, rating={}, tags={}", traceId, request.rating(), tags);
  }

  /**
   * 基于 QueryDSL 的多条件分页查询观测链路
   *
   * @param request 分页查询参数 (继承 BasePageRequest)
   * @return 分页链路列表
   */
  @Transactional(readOnly = true)
  public Page<RagTraceEntity> pageTraces(RagTracePageRequest request) {
    if (request == null) {
      return Page.empty();
    }
    QRagTraceEntity qTrace = QRagTraceEntity.ragTraceEntity;
    BooleanBuilder builder = new BooleanBuilder();
    if (request.getWorkspaceId() != null) {
      builder.and(qTrace.workspaceId.eq(request.getWorkspaceId()));
    }
    if (StrUtil.isNotBlank(request.getSessionId())) {
      builder.and(qTrace.sessionId.eq(request.getSessionId()));
    }
    if (request.getFeedbackRating() != null) {
      builder.and(qTrace.feedbackRating.eq(request.getFeedbackRating()));
    }
    if (StrUtil.isNotBlank(request.getCallSource())) {
      AiCallSourceEnum sourceEnum = AiCallSourceEnum.from(request.getCallSource(), null);
      if (sourceEnum != null) {
        builder.and(qTrace.callSource.eq(sourceEnum.getCode()));
      }
    }
    if (StrUtil.isNotBlank(request.getApiKey())) {
      builder.and(qTrace.apiKey.containsIgnoreCase(request.getApiKey().trim()));
    }
    return builder.getValue() == null
        ? ragTraceRepository.findAll(request.toPageable())
        : ragTraceRepository.findAll(builder, request.toPageable());
  }

  /**
   * 基于 QueryDSL 查询单条观测链路详情 (含快照 JSON)
   *
   * @param traceId 链路 ID
   * @return 链路快照明细
   */
  @Transactional(readOnly = true)
  public Optional<RagTraceDetailEntity> getDetailByTraceId(String traceId) {
    if (StrUtil.isBlank(traceId)) {
      return Optional.empty();
    }
    QRagTraceDetailEntity qDetail = QRagTraceDetailEntity.ragTraceDetailEntity;
    // 回答的内容这些只能看到自己的 无论是不是管理员还是拥有者
    return ragTraceDetailRepository.findOne(
        qDetail.traceId.eq(traceId).and(qDetail.createBy.eq(SecurityHolder.getUserId())));
  }

  private String toJsonSafe(Object obj) {
    if (obj == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(obj);
    } catch (Exception e) {
      log.warn("【RagTrace】对象序列化为 JSON 异常: {}", e.getMessage());
      return "[]";
    }
  }
}
