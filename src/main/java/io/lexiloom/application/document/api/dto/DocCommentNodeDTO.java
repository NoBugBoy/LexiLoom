package io.lexiloom.application.document.api.dto;

import cn.hutool.core.date.DatePattern;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/** 协同评论树节点传输对象 (不可变 record) */
public record DocCommentNodeDTO(
    Long id,
    Long workSpaceId,
    Long documentId,
    Long userId,
    Long parentId,
    Long replyToUserId,
    String content,
    Integer likeCount,
    @JsonFormat(pattern = DatePattern.NORM_DATETIME_PATTERN) LocalDateTime createTime,
    List<DocCommentNodeDTO> replies) {

  public DocCommentNodeDTO {
    replies = (replies == null) ? Collections.emptyList() : Collections.unmodifiableList(replies);
  }
}
