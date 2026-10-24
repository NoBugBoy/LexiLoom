package io.lexiloom.application.ai.trace.model;

import jakarta.validation.constraints.NotNull;
import java.util.Collections;
import java.util.List;

/**
 * 用户端对特定 RAG 问答生成的评估反馈入参 (不可变 record)
 *
 * @param rating 评估等级：1 为点赞 (回答准确有帮助)，-1 为点踩 (存在幻觉/检索不准)，0 为取消评价
 * @param tags 结构化归因标签 (如: "HALLUCINATION", "IRRELEVANT_CITATION", "MISSING_INFO")
 * @param comment 具体的文本评价与纠偏建议
 */
public record RagFeedbackRequest(
    @NotNull(message = "评价等级不能为空") Integer rating, List<String> tags, String comment) {

  public RagFeedbackRequest {
    tags = (tags != null) ? List.copyOf(tags) : Collections.emptyList();
  }
}
