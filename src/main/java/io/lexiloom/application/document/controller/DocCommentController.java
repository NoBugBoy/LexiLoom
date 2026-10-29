package io.lexiloom.application.document.controller;

import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.document.api.dto.DocCommentDTO;
import io.lexiloom.application.document.api.dto.DocCommentNodeDTO;
import io.lexiloom.application.document.comment.DocCommentService;
import io.lexiloom.application.document.model.request.DocCommentCreateRequest;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
    return docCommentService.addComment(workSpaceId, docId, userId, request);
  }

  /** 获取文档评论树 */
  @GetMapping
  public List<DocCommentNodeDTO> getCommentTree(@PathVariable("docId") Long docId) {
    Long userId = SecurityHolder.getUserId();
    return docCommentService.getCommentTree(docId, userId);
  }

  /** 删除评论 */
  @DeleteMapping("/{commentId}")
  public ResponseEntity<Void> deleteComment(
      @PathVariable("docId") Long docId, @PathVariable("commentId") Long commentId) {
    Long userId = SecurityHolder.getUserId();
    docCommentService.deleteComment(commentId, userId);
    return ResponseEntity.noContent().build();
  }

  /** 点赞评论 */
  @PostMapping("/{commentId}/like")
  public ResponseEntity<Void> likeComment(
      @PathVariable("docId") Long docId, @PathVariable("commentId") Long commentId) {
    Long userId = SecurityHolder.getUserId();
    docCommentService.likeComment(commentId, userId);
    return ResponseEntity.ok().build();
  }
}
