package io.lexiloom.application.document.queue;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.document.model.entity.DocSourceFileEntity;
import io.lexiloom.application.document.model.entity.DocumentEntity;
import io.lexiloom.application.document.model.event.DocumentUpdatedEvent;
import io.lexiloom.application.document.parser.DocParseCommand;
import io.lexiloom.application.document.parser.DocParseResult;
import io.lexiloom.application.document.parser.DocParseStatusEnum;
import io.lexiloom.application.document.parser.DocParserEngineEnum;
import io.lexiloom.application.document.parser.DocParserGateway;
import io.lexiloom.application.document.pipeline.DocumentImportTransactionalService;
import io.lexiloom.application.document.repository.DocSourceFileRepository;
import io.lexiloom.application.document.repository.DocumentRepository;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * DeepDoc 文档解析 Redis 分布式排队与单并发调度服务
 *
 * <p>核心契约：
 * <ul>
 *   <li>严格单并发：同时仅允许 1 个文档提交给 DeepDoc 进行解析，每完成一个再调度下一个；</li>
 *   <li>状态与排位：支持 QUEUED 排队状态，通过 Redis ZSet 实时计算当前排第几位；</li>
 *   <li>崩溃高可靠：服务关闭/重启后任务留存 Redis 中，启动后自动断点续调；</li>
 *   <li>固定重试：若 DeepDoc 服务未启动或瞬时网络断开，保留在队首并按固定周期轮询重试（无指数退避）；</li>
 *   <li>幂等强一致：仅在接收到 DeepDoc 异步回调且成功入库落盘后，才将任务移出队列。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocParseQueueService {

  public static final String QUEUE_KEY = "ll:doc:parse:queue";
  public static final String TASK_PREFIX = "ll:doc:parse:task:";
  public static final String ACTIVE_KEY = "ll:doc:parse:active";
  private static final Duration ACTIVE_TTL = Duration.ofMinutes(60);

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final DocParserGateway docParserGateway;
  private final DocSourceFileRepository docSourceFileRepository;
  private final DocumentRepository documentRepository;
  private final DocumentImportTransactionalService transactionalService;
  private final ApplicationEventPublisher eventPublisher;

  private final ReentrantLock localDispatchLock = new ReentrantLock();

  /**
   * 将文档解析任务压入 Redis 队列
   *
   * @param task 解析任务载荷
   */
  public void enqueue(DocParseTaskItem task) {
    if (task == null || task.documentId() == null) {
      return;
    }
    String docIdStr = String.valueOf(task.documentId());
    try {
      String json = objectMapper.writeValueAsString(task);
      redisTemplate.opsForValue().set(TASK_PREFIX + docIdStr, json);
      redisTemplate.opsForZSet().add(QUEUE_KEY, docIdStr, (double) task.createTime());

      log.info(
          "【DocParseQueue】文档成功进入 Redis 解析排队队列: docId={}, file={}, queueRank={}",
          task.documentId(),
          task.fileName(),
          getQueuePosition(task.documentId()));

      triggerNextDispatch();
    } catch (Exception e) {
      log.error("【DocParseQueue】任务写入 Redis 队列失败: docId={}, error={}", task.documentId(), e.getMessage(), e);
    }
  }

  /**
   * 查询指定文档在排队队列中的排位
   *
   * @param documentId 文档主键 ID
   * @return 1-based 排位序号（第 1 位、第 2 位...）；若当前正在 DeepDoc 解析中返回 0；若不在队列返回 null
   */
  @Nullable
  public Integer getQueuePosition(Long documentId) {
    if (documentId == null) {
      return null;
    }
    String docIdStr = String.valueOf(documentId);
    String activeDocId = redisTemplate.opsForValue().get(ACTIVE_KEY);
    if (docIdStr.equals(activeDocId)) {
      return 0; // 当前正在解析中
    }

    Long rank = redisTemplate.opsForZSet().rank(QUEUE_KEY, docIdStr);
    if (rank == null) {
      return null;
    }
    return (int) (rank + 1);
  }

  /**
   * 触发下一个排队任务派发执行（保证集群全局 DeepDoc 单并发）
   */
  public void triggerNextDispatch() {
    if (!localDispatchLock.tryLock()) {
      return;
    }
    try {
      // 1. 检查当前是否已有正在被 DeepDoc 处理的任务
      String activeDocId = redisTemplate.opsForValue().get(ACTIVE_KEY);
      if (StrUtil.isNotBlank(activeDocId)) {
        log.debug("【DocParseQueue】当前已有文档在 DeepDoc 执行中，保持等待: activeDocId={}", activeDocId);
        return;
      }

      // 2. 取出 ZSet 队首任务 (rank 0)
      Set<String> head = redisTemplate.opsForZSet().range(QUEUE_KEY, 0, 0);
      if (head == null || head.isEmpty()) {
        return;
      }
      String headDocIdStr = head.iterator().next();

      // 3. 抢占全局 active 槽位 (SETNX 带 TTL 兜底防死锁)
      Boolean acquired = redisTemplate.opsForValue().setIfAbsent(ACTIVE_KEY, headDocIdStr, ACTIVE_TTL);
      if (acquired == null || !acquired) {
        log.debug("【DocParseQueue】抢占执行槽位竞争失败，由其他节点处理: headDocId={}", headDocIdStr);
        return;
      }

      log.info("【DocParseQueue】成功抢占 DeepDoc 解析执行槽位: docId={}", headDocIdStr);

      // 使用 JDK 25 虚拟线程异步执行派发，避免阻塞调度主线程
      Thread.ofVirtual().start(() -> executeDispatch(headDocIdStr));
    } finally {
      localDispatchLock.unlock();
    }
  }

  /**
   * 真正执行单个任务向 DeepDoc 发起解析提交
   */
  private void executeDispatch(String docIdStr) {
    Long docId;
    try {
      docId = Long.parseLong(docIdStr);
    } catch (NumberFormatException e) {
      log.warn("【DocParseQueue】非法 docId，剔除队首: {}", docIdStr);
      redisTemplate.opsForZSet().remove(QUEUE_KEY, docIdStr);
      redisTemplate.delete(ACTIVE_KEY);
      triggerNextDispatch();
      return;
    }

    DocParseTaskItem task = loadTask(docId);
    if (task == null) {
      task = buildTaskFromDb(docId);
      if (task == null) {
        log.warn("【DocParseQueue】无法加载任务元数据且 DB 无记录，剔除队首: docId={}", docId);
        redisTemplate.opsForZSet().remove(QUEUE_KEY, docIdStr);
        redisTemplate.delete(ACTIVE_KEY);
        triggerNextDispatch();
        return;
      }
    }

    DocSourceFileEntity sourceFile =
        task.sourceFileId() != null
            ? docSourceFileRepository.findById(task.sourceFileId()).orElse(null)
            : null;

    String storagePath =
        task.storagePath() != null
            ? task.storagePath()
            : (sourceFile != null ? sourceFile.getStoragePath() : null);

    Map<String, Object> extraOptions = new HashMap<>();
    if (task.fileName() != null && task.fileName().toLowerCase().endsWith(".pdf")) {
      extraOptions.put("ocr", true);
    }
    extraOptions.put("documentId", task.documentId());
    if (task.sourceFileId() != null) {
      extraOptions.put("sourceFileId", task.sourceFileId());
    }
    if (task.workspaceId() != null) {
      extraOptions.put("workspaceId", task.workspaceId());
    }
    if (task.operatorId() != null) {
      extraOptions.put("operatorId", task.operatorId());
    }

    DocParseCommand parseCmd =
        DocParseCommand.of(
            null,
            storagePath,
            task.fileName(),
            task.mimeType() != null ? task.mimeType() : "application/octet-stream",
            task.engine() != null ? task.engine() : DocParserEngineEnum.AUTO,
            extraOptions);

    try {
      log.info("【DocParseQueue】向解析网关提交解析: docId={}, file={}", task.documentId(), task.fileName());
      DocParseResult parseResult = docParserGateway.parse(parseCmd);

      if (parseResult.isAsyncProcessing()) {
        // 远程微服务已接单，更新数据库为 ASYNC_PROCESSING，等待 HTTP 回调
        log.info(
            "【DocParseQueue】DeepDoc 已成功接收任务，进入等待回调模式: docId={}, taskId={}",
            task.documentId(),
            parseResult.metadata().get("taskId"));
        transactionalService.markProcessing(task.documentId(), task.sourceFileId());
        // 关键点：任务留在 Redis 队列队首，保持 ACTIVE_KEY，等待回调完成后再彻底移除
        return;
      }

      if (parseResult.isSuccess() || parseResult.isPreviewOnly()) {
        // 同步返回解析结果（非 DeepDoc 回调引擎或纯预览模式）
        transactionalService.completeAsyncParse(
            task.documentId(),
            task.sourceFileId(),
            parseResult.markdownContent(),
            parseResult.rawText(),
            parseResult.suggestedTitle(),
            parseResult.engineUsed(),
            parseResult.metadata());
        eventPublisher.publishEvent(
            new DocumentUpdatedEvent(task.documentId(), task.workspaceId(), task.operatorId()));
        onParseCompleted(task.documentId());
        return;
      }

      if (isDeepDocUnavailable(parseResult)) {
        // DeepDoc 服务未启动或瞬时网络断开：释放 active 槽位，保留在队首，等待下一次定时器无指数退避重试
        log.warn(
            "【DocParseQueue】DeepDoc 远程服务未就绪或未启动，释放 active 槽位保留队首待重试: docId={}, error={}",
            task.documentId(),
            parseResult.errorMessage());
        redisTemplate.delete(ACTIVE_KEY);
        return;
      }

      // 文件严重损坏等不可重试的硬错误
      log.error(
          "【DocParseQueue】文档解析发生非可重试错误: docId={}, error={}",
          task.documentId(),
          parseResult.errorMessage());
      transactionalService.failAsyncParse(task.documentId(), task.sourceFileId(), parseResult.errorMessage());
      onParseCompleted(task.documentId());
    } catch (Exception e) {
      if (isNetworkException(e)) {
        log.warn(
            "【DocParseQueue】调用 DeepDoc 网络异常，释放 active 槽位待重试: docId={}, error={}",
            task.documentId(),
            e.getMessage());
        redisTemplate.delete(ACTIVE_KEY);
      } else {
        log.error("【DocParseQueue】提交解析出现未知异常: docId={}, error={}", task.documentId(), e.getMessage(), e);
        transactionalService.failAsyncParse(task.documentId(), task.sourceFileId(), e.getMessage());
        onParseCompleted(task.documentId());
      }
    }
  }

  /**
   * 当收到 DeepDoc 解析完成回调并成功入库后调用：彻底删除 Redis 任务并调度下一个
   *
   * @param documentId 文档主键 ID
   */
  public void onParseCompleted(Long documentId) {
    if (documentId == null) {
      return;
    }
    String docIdStr = String.valueOf(documentId);
    try {
      redisTemplate.opsForZSet().remove(QUEUE_KEY, docIdStr);
      redisTemplate.delete(TASK_PREFIX + docIdStr);
      redisTemplate.delete(ACTIVE_KEY);
      log.info("【DocParseQueue】任务已成功完成并彻底移出 Redis 队列: docId={}", documentId);
    } catch (Exception e) {
      log.error("【DocParseQueue】清理已完成任务异常: docId={}, error={}", documentId, e.getMessage(), e);
    }

    // 立即发起下一个排队文档解析
    triggerNextDispatch();
  }

  /**
   * 定时巡检与重试调度器（默认每 5 秒轮询一次，无需指数退避）
   */
  @Scheduled(fixedDelayString = "${lexiloom.parser.deepdoc.queue-poll-interval-ms:5000}")
  public void scheduledPollAndRetry() {
    triggerNextDispatch();
  }

  /**
   * 应用启动就绪后自动巡检 Redis 队列，保障服务重启后任务无缝恢复
   */
  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    log.info("【DocParseQueue】LexiLoom 服务启动就绪，巡检 Redis 排队队列并恢复可能未完成的任务...");
    String activeDocId = redisTemplate.opsForValue().get(ACTIVE_KEY);
    if (StrUtil.isNotBlank(activeDocId)) {
      try {
        Long docId = Long.parseLong(activeDocId);
        DocumentEntity doc = documentRepository.findById(docId).orElse(null);
        if (doc != null && doc.getParseStatus() == DocParseStatusEnum.QUEUED) {
          log.info("【DocParseQueue】发现上次宕机残留的未提交 active 任务，释放槽位重新派发: docId={}", docId);
          redisTemplate.delete(ACTIVE_KEY);
        }
      } catch (Exception ignored) {
        redisTemplate.delete(ACTIVE_KEY);
      }
    }
    triggerNextDispatch();
  }

  private boolean isDeepDocUnavailable(DocParseResult result) {
    if (result == null || !result.isFailure()) {
      return false;
    }
    String msg = String.valueOf(result.errorMessage()).toLowerCase();
    return msg.contains("未就绪")
        || msg.contains("未配置")
        || msg.contains("连接断开")
        || msg.contains("connection refused")
        || msg.contains("reset by peer")
        || msg.contains("socketexception");
  }

  private boolean isNetworkException(Throwable e) {
    if (e == null) {
      return false;
    }
    String msg = String.valueOf(e.getMessage()).toLowerCase();
    return msg.contains("connection refused")
        || msg.contains("connectexception")
        || msg.contains("socketexception")
        || msg.contains("resourceaccessexception");
  }

  @Nullable
  private DocParseTaskItem loadTask(Long docId) {
    try {
      String json = redisTemplate.opsForValue().get(TASK_PREFIX + docId);
      if (StrUtil.isNotBlank(json)) {
        return objectMapper.readValue(json, DocParseTaskItem.class);
      }
    } catch (Exception e) {
      log.warn("【DocParseQueue】反序列化任务元数据失败: docId={}", docId, e);
    }
    return null;
  }

  @Nullable
  private DocParseTaskItem buildTaskFromDb(Long docId) {
    return documentRepository
        .findById(docId)
        .map(
            doc -> {
              DocSourceFileEntity sf =
                  doc.getSourceFileId() != null
                      ? docSourceFileRepository.findById(doc.getSourceFileId()).orElse(null)
                      : null;
              return new DocParseTaskItem(
                  doc.getId(),
                  doc.getSourceFileId(),
                  doc.getWorkSpaceId(),
                  doc.getCreateBy(),
                  sf != null && sf.getParseEngine() != null
                      ? sf.getParseEngine()
                      : DocParserEngineEnum.DEEPDOC,
                  sf != null ? sf.getOriginalFileName() : doc.getTitle(),
                  sf != null ? sf.getStoragePath() : null,
                  sf != null ? sf.getMimeType() : null,
                  System.currentTimeMillis());
            })
        .orElse(null);
  }
}
