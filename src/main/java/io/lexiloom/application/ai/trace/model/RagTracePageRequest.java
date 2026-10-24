package io.lexiloom.application.ai.trace.model;

import io.lexiloom.application.common.BasePageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** RAG 观测链路分页查询请求 (继承 BasePageRequest) */
@Data
@EqualsAndHashCode(callSuper = true)
public class RagTracePageRequest extends BasePageRequest {

  /** 所属工作空间 ID */
  private Long workspaceId;

  /** 对话会话唯一标识 */
  private String sessionId;

  /** 用户评估反馈评分 (1: 点赞, -1: 点踩, 0: 未评价) */
  private Integer feedbackRating;

  /** 调用来源 (受管枚举: CLI_SKILL, OPENAPI, WEB_CHAT) */
  private String callSource;

  /** 调用凭证 API Key */
  private String apiKey;
}
