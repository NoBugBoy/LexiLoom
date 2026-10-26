package io.lexiloom.application.ai.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ChatMessage;
import io.lexiloom.application.ai.model.vo.CitationVO;
import io.lexiloom.application.ai.model.vo.ConversationMessageVO;
import io.lexiloom.application.ai.model.vo.ConversationSessionVO;
import io.lexiloom.application.ai.model.vo.ToolEventVO;
import io.lexiloom.application.exception.BusinessException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ConversationSessionServiceTest {

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOperations;
  @Mock private ZSetOperations<String, String> zSetOperations;

  private ObjectMapper objectMapper;
  private AiProperties aiProperties;
  private ConversationSessionService sessionService;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    aiProperties = new AiProperties();
    aiProperties.getRag().setHistoryTurns(2); // 最多 2 轮 = 4 条消息
    aiProperties.getRag().setSessionExpireSeconds(604800L); // 7 天

    sessionService = new ConversationSessionService(redisTemplate, objectMapper, aiProperties);
  }

  @Test
  @DisplayName("验证轻量会话 ID 创建与存在性校验")
  void testCreateAndCheckSession() {
    String sessionId = sessionService.createSession();
    assertThat(sessionId).isNotBlank();

    when(redisTemplate.hasKey(ConversationSessionService.SESSION_META_PREFIX + sessionId))
        .thenReturn(true);
    assertThat(sessionService.sessionExists(sessionId)).isTrue();

    when(redisTemplate.hasKey(ConversationSessionService.SESSION_META_PREFIX + sessionId))
        .thenReturn(false);
    when(redisTemplate.hasKey(ConversationSessionService.SESSION_KEY_PREFIX + sessionId))
        .thenReturn(false);
    assertThat(sessionService.sessionExists(sessionId)).isFalse();

    assertThat(sessionService.sessionExists("")).isFalse();
    assertThat(sessionService.sessionExists(null)).isFalse();
  }

  @Test
  @DisplayName("验证显式创建会话：写入元数据并维护用户 ZSet 索引")
  void testCreateSessionWithMeta() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

    ConversationSessionVO meta = sessionService.createSession(1L, 100L, "测试标题");

    assertThat(meta.sessionId()).isNotBlank();
    assertThat(meta.title()).isEqualTo("测试标题");
    assertThat(meta.workspaceId()).isEqualTo(1L);
    assertThat(meta.userId()).isEqualTo(100L);

    verify(valueOperations)
        .set(
            eq(ConversationSessionService.SESSION_META_PREFIX + meta.sessionId()),
            anyString(),
            eq(Duration.ofSeconds(604800L)));

    verify(zSetOperations)
        .add(
            eq(ConversationSessionService.USER_SESSIONS_PREFIX + "100:1"),
            eq(meta.sessionId()),
            anyDouble());
  }

  @Test
  @DisplayName("验证读取会话历史与自动滑动续期 7 天")
  void testGetHistoryWithSlidingExpire() throws Exception {
    String sessionId = "test-session-1";
    String key = ConversationSessionService.SESSION_KEY_PREFIX + sessionId;

    List<ConversationMessageVO> origin =
        List.of(
            new ConversationMessageVO("user", "你好", "2026-10-13 10:00:00"),
            new ConversationMessageVO("assistant", "你好，我是知识库助手！", "2026-10-13 10:00:02"));
    String json = objectMapper.writeValueAsString(origin);

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(key)).thenReturn(json);

    List<ChatMessage> history = sessionService.getHistory(sessionId);

    assertThat(history).hasSize(2);
    assertThat(history.get(0).role()).isEqualTo("user");
    assertThat(history.get(0).content()).isEqualTo("你好");
    assertThat(history.get(1).role()).isEqualTo("assistant");

    // 验证滑动续期调用 7 天
    verify(redisTemplate).expire(eq(key), eq(Duration.ofSeconds(604800L)));
  }

  @Test
  @DisplayName("验证前端拉取完整历史与喂给大模型的多轮窗口截断")
  void testFullHistoryVsLlmHistory() throws Exception {
    String sessionId = "sess_multi";
    String key = ConversationSessionService.SESSION_KEY_PREFIX + sessionId;

    // 模拟会话中有 6 条消息 (3 轮)
    List<ConversationMessageVO> fullList =
        List.of(
            new ConversationMessageVO("user", "第1问", "2026-10-13 10:00:00"),
            new ConversationMessageVO("assistant", "第1答", "2026-10-13 10:00:01"),
            new ConversationMessageVO("user", "第2问", "2026-10-13 10:01:00"),
            new ConversationMessageVO("assistant", "第2答", "2026-10-13 10:01:01"),
            new ConversationMessageVO("user", "第3问", "2026-10-13 10:02:00"),
            new ConversationMessageVO("assistant", "第3答", "2026-10-13 10:02:01"));

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(key)).thenReturn(objectMapper.writeValueAsString(fullList));

    // 1. 前端获取完整历史：全部 6 条都在
    List<ConversationMessageVO> fullHistory = sessionService.getFullHistory(sessionId);
    assertThat(fullHistory).hasSize(6);

    // 2. 大模型获取历史：根据 historyTurns=2 截取最近 4 条
    List<ChatMessage> llmHistory = sessionService.getHistory(sessionId);
    assertThat(llmHistory).hasSize(4);
    assertThat(llmHistory.get(0).content()).isEqualTo("第2问");
    assertThat(llmHistory.get(3).content()).isEqualTo("第3答");
  }

  @Test
  @DisplayName("验证查询工作空间下的用户会话列表（按时间倒序）")
  void testListSessions() throws Exception {
    when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    String zsetKey = ConversationSessionService.USER_SESSIONS_PREFIX + "100:1";
    Set<String> sessionIds = new LinkedHashSet<>(List.of("s2", "s1"));
    when(zSetOperations.reverseRange(zsetKey, 0, -1)).thenReturn(sessionIds);

    ConversationSessionVO m1 =
        new ConversationSessionVO(
            "s1", "第1会话", 1L, 100L, 2, "2026-10-13 10:00:00", "2026-10-13 10:00:00");
    ConversationSessionVO m2 =
        new ConversationSessionVO(
            "s2", "第2会话", 1L, 100L, 4, "2026-10-13 11:00:00", "2026-10-13 11:00:00");

    when(valueOperations.get(ConversationSessionService.SESSION_META_PREFIX + "s1"))
        .thenReturn(objectMapper.writeValueAsString(m1));
    when(valueOperations.get(ConversationSessionService.SESSION_META_PREFIX + "s2"))
        .thenReturn(objectMapper.writeValueAsString(m2));

    List<ConversationSessionVO> list = sessionService.listSessions(1L, 100L);

    assertThat(list).hasSize(2);
    assertThat(list.get(0).sessionId()).isEqualTo("s2");
    assertThat(list.get(1).sessionId()).isEqualTo("s1");
  }

  @Test
  @DisplayName("验证会话删除：销毁消息、元数据及索引")
  void testDeleteSession() throws Exception {
    String sessionId = "sess_del";
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

    ConversationSessionVO meta =
        new ConversationSessionVO(
            sessionId, "删除会话", 1L, 100L, 0, "2026-10-13 10:00:00", "2026-10-13 10:00:00");
    when(valueOperations.get(ConversationSessionService.SESSION_META_PREFIX + sessionId))
        .thenReturn(objectMapper.writeValueAsString(meta));

    sessionService.deleteSession(sessionId, 100L);

    verify(zSetOperations)
        .remove(ConversationSessionService.USER_SESSIONS_PREFIX + "100:1", sessionId);
    verify(redisTemplate).delete(any(List.class));
  }

  @Test
  @DisplayName("验证越权删除他人会话抛出 403 异常")
  void testDeleteSessionForbidden() throws Exception {
    String sessionId = "sess_other";
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);

    ConversationSessionVO meta =
        new ConversationSessionVO(
            sessionId, "他人会话", 1L, 200L, 0, "2026-10-13 10:00:00", "2026-10-13 10:00:00");
    when(valueOperations.get(ConversationSessionService.SESSION_META_PREFIX + sessionId))
        .thenReturn(objectMapper.writeValueAsString(meta));

    assertThatThrownBy(() -> sessionService.deleteSession(sessionId, 100L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("无权删除他人会话");
  }

  @Test
  @DisplayName("验证 Redis 异常时的防崩溃平滑降级")
  void testRedisExceptionResilience() {
    String sessionId = "sess_err";
    when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis connection timed out"));

    List<ChatMessage> history = sessionService.getHistory(sessionId);
    assertThat(history).isEmpty();

    // append 不应抛出未捕获异常
    sessionService.appendUserMessage(sessionId, "问");
  }

  @Test
  @DisplayName("验证助手回答携带 citations 视觉溯源卡片与 thinking 思维链的持久化")
  void testAppendAssistantMessageWithCitationsAndThinking() {
    String sessionId = "sess_citation_test";
    String key = ConversationSessionService.SESSION_KEY_PREFIX + sessionId;

    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(key)).thenReturn(null);

    CitationVO citation =
        new CitationVO(
            1, 50L, "架构规范", "模块设计", "系统模块包含脚本管理...", 0.95, 1, List.of(10f, 20f), "text", "c1");

    sessionService.appendAssistantMessage(sessionId, "回答内容", List.of(citation), "深度思考过程...");

    verify(valueOperations)
        .set(
            eq(key),
            org.mockito.ArgumentMatchers.argThat(
                jsonStr -> {
                  try {
                    ConversationMessageVO[] arr =
                        objectMapper.readValue(jsonStr, ConversationMessageVO[].class);
                    return arr.length == 1
                        && arr[0].role().equals("assistant")
                        && arr[0].content().equals("回答内容")
                        && arr[0].citations().size() == 1
                        && arr[0].citations().get(0).docTitle().equals("架构规范")
                        && "深度思考过程...".equals(arr[0].thinking());
                  } catch (Exception e) {
                    return false;
                  }
                }),
            eq(Duration.ofSeconds(604800L)));
  }

  @Test
  @DisplayName("验证助手回答关联的工具调用摘要写入 Redis 历史")
  void testAppendAssistantMessageWithToolEvents() {
    String sessionId = "sess_tools_test";
    String key = ConversationSessionService.SESSION_KEY_PREFIX + sessionId;
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(key)).thenReturn(null);

    List<ToolEventVO> events =
        List.of(
            new ToolEventVO("search_knowledge", "started", "检索问题", null),
            new ToolEventVO("search_knowledge", "completed", "检索问题", 2));
    sessionService.appendAssistantMessage(sessionId, "回答内容", List.of(), null, events);

    verify(valueOperations)
        .set(
            eq(key),
            org.mockito.ArgumentMatchers.argThat(
                json -> {
                  try {
                    ConversationMessageVO[] arr =
                        objectMapper.readValue(json, ConversationMessageVO[].class);
                    return arr.length == 1
                        && arr[0].tools().size() == 2
                        && arr[0].tools().get(1).status().equals("completed")
                        && arr[0].tools().get(1).resultCount() == 2;
                  } catch (Exception exception) {
                    return false;
                  }
                }),
            eq(Duration.ofSeconds(604800L)));
  }
}
