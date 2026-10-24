package io.lexiloom.application.document.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lexiloom.application.document.model.entity.DocSourceFileEntity;
import io.lexiloom.application.document.model.entity.DocumentEntity;
import io.lexiloom.application.document.parser.DocParseCommand;
import io.lexiloom.application.document.parser.DocParseResult;
import io.lexiloom.application.document.parser.DocParserEngineEnum;
import io.lexiloom.application.document.parser.DocParserGateway;
import io.lexiloom.application.document.pipeline.DocumentImportTransactionalService;
import io.lexiloom.application.document.repository.DocSourceFileRepository;
import io.lexiloom.application.document.repository.DocumentRepository;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import tools.jackson.databind.ObjectMapper;

@DisplayName("DocParseQueueService Redis 排队与单并发调度服务测试")
class DocParseQueueServiceTest {

  private StringRedisTemplate redisTemplate;
  private ValueOperations<String, String> valueOperations;
  private ZSetOperations<String, String> zSetOperations;
  private ObjectMapper objectMapper;
  private DocParserGateway docParserGateway;
  private DocSourceFileRepository docSourceFileRepository;
  private DocumentRepository documentRepository;
  private DocumentImportTransactionalService transactionalService;
  private ApplicationEventPublisher eventPublisher;

  private DocParseQueueService queueService;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    redisTemplate = mock(StringRedisTemplate.class);
    valueOperations = mock(ValueOperations.class);
    zSetOperations = mock(ZSetOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

    objectMapper = new ObjectMapper();
    docParserGateway = mock(DocParserGateway.class);
    docSourceFileRepository = mock(DocSourceFileRepository.class);
    documentRepository = mock(DocumentRepository.class);
    transactionalService = mock(DocumentImportTransactionalService.class);
    eventPublisher = mock(ApplicationEventPublisher.class);

    queueService =
        new DocParseQueueService(
            redisTemplate,
            objectMapper,
            docParserGateway,
            docSourceFileRepository,
            documentRepository,
            transactionalService,
            eventPublisher);
  }

  @Test
  @DisplayName("enqueue - 正常任务入队：保存 JSON 载荷并加入 ZSet 排队")
  void testEnqueue() {
    DocParseTaskItem task =
        new DocParseTaskItem(
            100L, 200L, 1L, 10L, DocParserEngineEnum.DEEPDOC, "test.pdf", "path/test.pdf", "application/pdf", 1000L);

    when(valueOperations.get(DocParseQueueService.ACTIVE_KEY)).thenReturn("999"); // 当前已有活跃任务，暂不 dispatch

    queueService.enqueue(task);

    verify(valueOperations).set(eq(DocParseQueueService.TASK_PREFIX + "100"), anyString());
    verify(zSetOperations).add(eq(DocParseQueueService.QUEUE_KEY), eq("100"), eq(1000.0));
  }

  @Test
  @DisplayName("getQueuePosition - 当前处于 active 状态时返回 0（处理中）")
  void testGetQueuePosition_Active() {
    when(valueOperations.get(DocParseQueueService.ACTIVE_KEY)).thenReturn("100");

    Integer pos = queueService.getQueuePosition(100L);
    assertThat(pos).isEqualTo(0);
  }

  @Test
  @DisplayName("getQueuePosition - 排队在队首未 dispatch 时返回 1")
  void testGetQueuePosition_Head() {
    when(valueOperations.get(DocParseQueueService.ACTIVE_KEY)).thenReturn(null);
    when(zSetOperations.rank(DocParseQueueService.QUEUE_KEY, "100")).thenReturn(0L);

    Integer pos = queueService.getQueuePosition(100L);
    assertThat(pos).isEqualTo(1);
  }

  @Test
  @DisplayName("getQueuePosition - 排在第 3 位时返回 3")
  void testGetQueuePosition_Third() {
    when(valueOperations.get(DocParseQueueService.ACTIVE_KEY)).thenReturn(null);
    when(zSetOperations.rank(DocParseQueueService.QUEUE_KEY, "100")).thenReturn(2L);

    Integer pos = queueService.getQueuePosition(100L);
    assertThat(pos).isEqualTo(3);
  }

  @Test
  @DisplayName("getQueuePosition - 不在队列中返回 null")
  void testGetQueuePosition_NotInQueue() {
    when(valueOperations.get(DocParseQueueService.ACTIVE_KEY)).thenReturn(null);
    when(zSetOperations.rank(DocParseQueueService.QUEUE_KEY, "100")).thenReturn(null);

    Integer pos = queueService.getQueuePosition(100L);
    assertThat(pos).isNull();
  }

  @Test
  @DisplayName("triggerNextDispatch - 当前已有活跃任务时，不发起下一个派发，严格保证单并发")
  void testTriggerNextDispatch_AlreadyActive() {
    when(valueOperations.get(DocParseQueueService.ACTIVE_KEY)).thenReturn("888");

    queueService.triggerNextDispatch();

    verify(zSetOperations, never()).range(anyString(), any(Long.class), any(Long.class));
    verify(docParserGateway, never()).parse(any());
  }

  @Test
  @DisplayName("onParseCompleted - 回调落库完成后彻底移出 Redis 队列并调度下一个")
  void testOnParseCompleted() {
    when(valueOperations.get(DocParseQueueService.ACTIVE_KEY)).thenReturn(null);
    when(zSetOperations.range(DocParseQueueService.QUEUE_KEY, 0, 0)).thenReturn(Set.of());

    queueService.onParseCompleted(100L);

    verify(zSetOperations).remove(DocParseQueueService.QUEUE_KEY, "100");
    verify(redisTemplate).delete(DocParseQueueService.TASK_PREFIX + "100");
    verify(redisTemplate).delete(DocParseQueueService.ACTIVE_KEY);
  }
}
