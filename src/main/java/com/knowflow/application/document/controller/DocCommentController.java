package com.knowflow.application.document.controller;

import com.knowflow.application.common.SecurityHolder;
import com.knowflow.application.document.api.dto.DocCommentDTO;
import com.knowflow.application.document.api.dto.DocCommentNodeDTO;
import com.knowflow.application.document.comment.DocCommentService;
import com.knowflow.application.document.model.request.DocCommentCreateRequest;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 文档协同评论 REST 控制器 */
@RestController
@RequestMapping("/documents/{docId}/comments")
@RequiredArgsConstructor
public class DocCommentController {

  private final DocCommentService docCommentService;

  /** 发表评论或楼中楼回复 */
  @PostMapping
  public DocCommentDTO addComment(
      @PathVariable("docId") Long docId,
      @RequestParam("workSpaceId") @NotNull(message = "工作空间ID不能为空") Long workSpaceId,
      @Validated @RequestBody DocCommentCreateRequest request) {
    Long userId = SecurityHolder.getUserId();

    if (request.parentId() == null || request.parentId() == 0L) {
      return docCommentService.addRootComment(workSpaceId, docId, userId, request.content());
    } else {
      return docCommentService.addReplyComment(
          workSpaceId,
          docId,
          userId,
          request.parentId(),
          request.replyToUserId(),
          request.content());
    }
  }

  /** 获取文档评论树 */
  @GetMapping
  public List<DocCommentNodeDTO> getCommentTree(@PathVariable("docId") Long docId) {
    Long userId = SecurityHolder.getUserId();
    return docCommentService.getCommentTree(docId, userId);
  }

  /** 删除评论 */
  @DeleteMapping("/{commentId}")
  public void deleteComment(
      @PathVariable("docId") Long docId, @PathVariable("commentId") Long commentId) {
    Long userId = SecurityHolder.getUserId();
    docCommentService.deleteComment(commentId, userId);
  }

  /** 点赞评论 */
  @PostMapping("/{commentId}/like")
  public void likeComment(
      @PathVariable("docId") Long docId, @PathVariable("commentId") Long commentId) {
    Long userId = SecurityHolder.getUserId();
    docCommentService.likeComment(commentId, userId);
  }
}
