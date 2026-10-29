package io.lexiloom.application.ai.rag;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.config.AiProperties;
import io.lexiloom.application.ai.gateway.llm.LlmChatCommand.ChatMessage;
import io.lexiloom.application.ai.model.vo.CitationVO;
import io.lexiloom.application.ai.model.vo.ConversationMessageVO;
import io.lexiloom.application.ai.model.vo.ConversationSessionVO;
import io.lexiloom.application.ai.model.vo.ToolEventVO;
import io.lexiloom.application.exception.BusinessException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * RAG 会话状态与多轮上下文管理服务 (ConversationSessionService)
 *
 * <p>基于 Redis 维护会话生命周期（默认 7 天滑动过期），提供：
 *
 * <ul>
 *   <li>多会话元数据（标题、时间戳、归属工作空间与用户）与多租户有序索引
 *   <li>会话历史消息全量存储与大模型上下文窗口截断
 *   <li>会话创建、查询列表、拉取历史、继续对话续期与删除销毁
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationSessionService {

  public static final String SESSION_KEY_PREFIX = "ll:ai:session:";
  public static final String SESSION_META_PREFIX = "ll:ai:session:meta:";
  public static final String USER_SESSIONS_PREFIX = "ll:ai:user_sessions:";
  private static final int MAX_HISTORY_MESSAGES = 100; // 单会话最大保存 100 条消息
  private static final DateTimeFormatter TIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final AiProperties aiProperties;

  /**
   * 创建新的对话会话 ID（轻量快速生成）
   *
   * @return 唯一会话 UUID
   */
  public String createSession() {
    return IdUtil.fastSimpleUUID();
  }

  /**
   * 显式创建新会话并注册元数据与用户索引
   *
   * @param workspaceId 所属工作空间 ID
   * @param userId 所属用户 ID
   * @param title 可选会话标题
   * @return 新建的会话元数据 VO
   */
  public ConversationSessionVO createSession(Long workspaceId, Long userId, String title) {
    String sessionId = IdUtil.fastSimpleUUID();
    String now = LocalDateTime.now().format(TIME_FORMATTER);
    String sessionTitle = StrUtil.isNotBlank(title) ? title.trim() : "新对话";

    ConversationSessionVO meta =
        new ConversationSessionVO(sessionId, sessionTitle, workspaceId, userId, 0, now, now);

    saveMeta(meta);
    if (userId != null && workspaceId != null) {
      addUserSessionIndex(userId, workspaceId, sessionId, System.currentTimeMillis());
    }

    log.info(
        "【ConversationSessionService】成功创建新会话: sessionId={}, wsId={}, userId={}, title={}",
        sessionId,
        workspaceId,
        userId,
        sessionTitle);
    return meta;
  }

  /**
   * 检查指定会话是否存在且未过期
   *
   * @param sessionId 会话唯一标识
   * @return true 若会话存在
   */
  public boolean sessionExists(String sessionId) {
    if (StrUtil.isBlank(sessionId)) {
      return false;
    }
    try {
      Boolean hasMeta = redisTemplate.hasKey(buildMetaKey(sessionId));
      if (Boolean.TRUE.equals(hasMeta)) {
        return true;
      }
      Boolean hasMsg = redisTemplate.hasKey(buildMessageKey(sessionId));
      return Boolean.TRUE.equals(hasMsg);
    } catch (Exception e) {
      log.warn(
          "【ConversationSessionService】检查会话是否存在失败: sessionId={}, error={}",
          sessionId,
          e.getMessage());
      return false;
    }
  }

  /**
   * 获取会话元数据（若存在则自动执行滑动过期续期）
   *
   * @param sessionId 会话唯一标识
   * @return 会话元数据 Optional
   */
  public Optional<ConversationSessionVO> getSessionMeta(String sessionId) {
    return readSessionMeta(sessionId, true);
  }

  private Optional<ConversationSessionVO> readSessionMeta(String sessionId, boolean refreshTtl) {
    if (StrUtil.isBlank(sessionId)) {
      return Optional.empty();
    }
    String metaKey = buildMetaKey(sessionId);
    try {
      String json = redisTemplate.opsForValue().get(metaKey);
      if (StrUtil.isBlank(json)) {
        return Optional.empty();
      }

      if (refreshTtl) {
        redisTemplate.expire(metaKey, Duration.ofSeconds(getExpireSeconds()));
      }

      ConversationSessionVO meta = objectMapper.readValue(json, ConversationSessionVO.class);
      return Optional.of(meta);
    } catch (Exception e) {
      log.warn(
          "【ConversationSessionService】读取会话元数据异常: sessionId={}, error={}",
          sessionId,
          e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * 获取指定工作空间下当前用户的全部会话列表（按最后更新时间倒序排序）
   *
   * @param workspaceId 工作空间 ID
   * @param userId 用户 ID
   * @return 会话元数据列表
   */
  public List<ConversationSessionVO> listSessions(Long workspaceId, Long userId) {
    if (workspaceId == null) {
      return List.of();
    }
    String zsetKey = buildUserSessionsKey(userId, workspaceId);
    try {
      redisTemplate.expire(zsetKey, Duration.ofSeconds(getExpireSeconds()));

      Set<String> sessionIds = redisTemplate.opsForZSet().reverseRange(zsetKey, 0, -1);
      if (sessionIds == null || sessionIds.isEmpty()) {
        return List.of();
      }

      List<ConversationSessionVO> list = new ArrayList<>();
      List<String> expiredSessionIds = new ArrayList<>();

      for (String sId : sessionIds) {
        getSessionMeta(sId).ifPresentOrElse(list::add, () -> expiredSessionIds.add(sId));
      }

      // 惰性移除已过期的会话索引
      if (!expiredSessionIds.isEmpty()) {
        redisTemplate.opsForZSet().remove(zsetKey, expiredSessionIds.toArray());
      }

      return Collections.unmodifiableList(list);
    } catch (Exception e) {
      log.warn(
          "【ConversationSessionService】查询用户会话列表异常: wsId={}, userId={}, error={}",
          workspaceId,
          userId,
          e.getMessage());
      return List.of();
    }
  }

  /**
   * 获取指定会话的完整历史消息列表（用于前端聊天窗口完整展示）
   *
   * @param sessionId 会话唯一标识
   * @return 完整历史消息列表
   */
  public List<ConversationMessageVO> getFullHistory(String sessionId) {
    return readFullHistory(sessionId, true);
  }

  private List<ConversationMessageVO> readFullHistory(String sessionId, boolean refreshTtl) {
    if (StrUtil.isBlank(sessionId)) {
      return List.of();
    }
    String key = buildMessageKey(sessionId);
    try {
      String json = redisTemplate.opsForValue().get(key);
      if (StrUtil.isBlank(json)) {
        return List.of();
      }

      if (refreshTtl) {
        redisTemplate.expire(key, Duration.ofSeconds(getExpireSeconds()));
        redisTemplate.expire(buildMetaKey(sessionId), Duration.ofSeconds(getExpireSeconds()));
      }

      ConversationMessageVO[] arr = objectMapper.readValue(json, ConversationMessageVO[].class);
      if (arr == null || arr.length == 0) {
        return List.of();
      }
      return List.of(arr);
    } catch (Exception e) {
      log.warn(
          "【ConversationSessionService】读取会话历史异常: sessionId={}, error={}",
          sessionId,
          e.getMessage());
      return List.of();
    }
  }

  /**
   * 获取喂给大模型的历史上下文（仅截取最近配置的轮数，避免 Token 超限）
   *
   * @param sessionId 会话唯一标识
   * @return 截取后的 ChatMessage 列表
   */
  public List<ChatMessage> getHistory(String sessionId) {
    List<ConversationMessageVO> fullHistory = getFullHistory(sessionId);
    if (fullHistory.isEmpty()) {
      return List.of();
    }

    int maxMessages = Math.max(aiProperties.getRag().getHistoryTurns() * 2, 2);
    List<ConversationMessageVO> recent = fullHistory;
    if (recent.size() > maxMessages) {
      recent = recent.subList(recent.size() - maxMessages, recent.size());
    }

    return recent.stream().map(m -> new ChatMessage(m.role(), m.content())).toList();
  }

  /** 追加用户提问消息（向后兼容重载） */
  public void appendUserMessage(String sessionId, String content) {
    appendUserMessage(sessionId, content, null, null);
  }

  /** 追加用户提问消息并更新工作空间与用户归属 */
  public void appendUserMessage(String sessionId, String content, Long workspaceId, Long userId) {
    appendMessage(sessionId, "user", content, List.of(), null, workspaceId, userId);
  }

  /** 追加助手回答消息（向后兼容重载） */
  public void appendAssistantMessage(String sessionId, String content) {
    appendAssistantMessage(sessionId, content, List.of(), null);
  }

  /** 追加助手回答消息（包含完整视觉溯源卡片与深度推理思考过程） */
  public void appendAssistantMessage(
      String sessionId, String content, List<CitationVO> citations, String thinking) {
    appendAssistantMessage(sessionId, content, citations, thinking, List.of());
  }

  /** Persist the assistant answer and sanitized tool timeline in one Redis history entry. */
  public void appendAssistantMessage(
      String sessionId,
      String content,
      List<CitationVO> citations,
      String thinking,
      List<ToolEventVO> tools) {
    appendMessage(sessionId, "assistant", content, citations, thinking, tools, null, null);
  }

  /** 向后兼容的 5 参数消息追加方法 */
  public void appendMessage(
      String sessionId, String role, String content, Long workspaceId, Long userId) {
    appendMessage(sessionId, role, content, List.of(), null, workspaceId, userId);
  }

  /** 追加消息、维护完整历史列表（含引用与思考过程）、自动推导标题并刷新 7 天滑动过期 */
  public void appendMessage(
      String sessionId,
      String role,
      String content,
      List<CitationVO> citations,
      String thinking,
      Long workspaceId,
      Long userId) {
    appendMessage(sessionId, role, content, citations, thinking, List.of(), workspaceId, userId);
  }

  /** Append a message with optional persisted tool events. */
  public void appendMessage(
      String sessionId,
      String role,
      String content,
      List<CitationVO> citations,
      String thinking,
      List<ToolEventVO> tools,
      Long workspaceId,
      Long userId) {
    if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(content)) {
      return;
    }

    String messageKey = buildMessageKey(sessionId);
    String now = LocalDateTime.now().format(TIME_FORMATTER);
    long nowEpoch = System.currentTimeMillis();

    try {
      // 1. 追加并持久化消息 (完整保留正文、时间戳、溯源卡片与思维链，写前跳过冗余 EXPIRE)
      List<ConversationMessageVO> existing = new ArrayList<>(readFullHistory(sessionId, false));
      existing.add(
          new ConversationMessageVO(
              role,
              content.trim(),
              now,
              citations != null ? citations : List.of(),
              thinking,
              tools != null ? tools : List.of()));

      if (existing.size() > MAX_HISTORY_MESSAGES) {
        existing = existing.subList(existing.size() - MAX_HISTORY_MESSAGES, existing.size());
      }

      String json = objectMapper.writeValueAsString(existing);
      redisTemplate.opsForValue().set(messageKey, json, Duration.ofSeconds(getExpireSeconds()));

      final int messageCount = existing.size();

      // 2. 维护/更新会话元数据与标题 (写前跳过冗余 EXPIRE)
      ConversationSessionVO meta =
          readSessionMeta(sessionId, false)
              .map(
                  oldMeta -> {
                    String title = oldMeta.title();
                    if (("新对话".equals(title) || StrUtil.isBlank(title)) && "user".equals(role)) {
                      title = StrUtil.sub(content.trim(), 0, 30);
                    }
                    Long actualWsId = workspaceId != null ? workspaceId : oldMeta.workspaceId();
                    Long actualUserId = userId != null ? userId : oldMeta.userId();
                    return new ConversationSessionVO(
                        sessionId,
                        title,
                        actualWsId,
                        actualUserId,
                        messageCount,
                        oldMeta.createTime(),
                        now);
                  })
              .orElseGet(
                  () -> {
                    String title = "user".equals(role) ? StrUtil.sub(content.trim(), 0, 30) : "新对话";
                    return new ConversationSessionVO(
                        sessionId, title, workspaceId, userId, messageCount, now, now);
                  });

      saveMeta(meta);

      // 3. 维护用户会话 ZSet 索引（按更新时间置顶）
      if (meta.userId() != null && meta.workspaceId() != null) {
        addUserSessionIndex(meta.userId(), meta.workspaceId(), sessionId, nowEpoch);
      }

      log.debug(
          "【ConversationSessionService】成功追加会话消息: sessionId={}, role={}, 当前总条数={}, 引用数={}",
          sessionId,
          role,
          existing.size(),
          citations != null ? citations.size() : 0);
    } catch (Exception e) {
      log.warn(
          "【ConversationSessionService】保存会话历史异常: sessionId={}, error={}",
          sessionId,
          e.getMessage());
    }
  }

  /**
   * 删除指定会话（校验归属、清除消息、元数据及索引）
   *
   * @param sessionId 会话唯一标识
   * @param currentUserId 当前操作用户 ID（若为 null 则跳过权限校验）
   */
  public void deleteSession(String sessionId, Long currentUserId) {
    if (StrUtil.isBlank(sessionId)) {
      return;
    }
    try {
      readSessionMeta(sessionId, false)
          .ifPresent(
              meta -> {
                if (currentUserId != null
                    && meta.userId() != null
                    && !meta.userId().equals(currentUserId)) {
                  throw BusinessException.forbidden("无权删除他人会话");
                }
                if (meta.userId() != null && meta.workspaceId() != null) {
                  String zsetKey = buildUserSessionsKey(meta.userId(), meta.workspaceId());
                  redisTemplate.opsForZSet().remove(zsetKey, sessionId);
                }
              });

      redisTemplate.delete(List.of(buildMessageKey(sessionId), buildMetaKey(sessionId)));
      log.info("【ConversationSessionService】成功销毁会话: sessionId={}", sessionId);
    } catch (BusinessException be) {
      throw be;
    } catch (Exception e) {
      log.warn(
          "【ConversationSessionService】删除会话异常: sessionId={}, error={}", sessionId, e.getMessage());
    }
  }

  /** 兼容性清理接口 */
  public void clearSession(String sessionId) {
    deleteSession(sessionId, null);
  }

  private void saveMeta(ConversationSessionVO meta) {
    try {
      String json = objectMapper.writeValueAsString(meta);
      redisTemplate
          .opsForValue()
          .set(buildMetaKey(meta.sessionId()), json, Duration.ofSeconds(getExpireSeconds()));
    } catch (Exception e) {
      log.warn(
          "【ConversationSessionService】保存会话元数据失败: sessionId={}, error={}",
          meta.sessionId(),
          e.getMessage());
    }
  }

  private void addUserSessionIndex(Long userId, Long workspaceId, String sessionId, double score) {
    try {
      String zsetKey = buildUserSessionsKey(userId, workspaceId);
      redisTemplate.opsForZSet().add(zsetKey, sessionId, score);
      redisTemplate.expire(zsetKey, Duration.ofSeconds(getExpireSeconds()));
    } catch (Exception e) {
      log.warn(
          "【ConversationSessionService】维护用户会话索引失败: zsetKey={}, error={}",
          buildUserSessionsKey(userId, workspaceId),
          e.getMessage());
    }
  }

  private String buildMessageKey(String sessionId) {
    return SESSION_KEY_PREFIX + sessionId.trim();
  }

  private String buildMetaKey(String sessionId) {
    return SESSION_META_PREFIX + sessionId.trim();
  }

  private String buildUserSessionsKey(Long userId, Long workspaceId) {
    return USER_SESSIONS_PREFIX
        + (userId != null ? userId : 0L)
        + ":"
        + (workspaceId != null ? workspaceId : 0L);
  }

  private long getExpireSeconds() {
    long sec = aiProperties.getRag().getSessionExpireSeconds();
    return sec > 0 ? sec : 604800L;
  }
}
