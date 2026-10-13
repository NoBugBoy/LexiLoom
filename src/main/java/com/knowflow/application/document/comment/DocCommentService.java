package com.knowflow.application.document.comment;

import com.knowflow.application.common.ErrorCode;
import com.knowflow.application.document.api.dto.DocCommentDTO;
import com.knowflow.application.document.api.dto.DocCommentNodeDTO;
import com.knowflow.application.document.mapper.DocCommentMapper;
import com.knowflow.application.document.model.entity.DocCommentEntity;
import com.knowflow.application.document.model.entity.DocumentEntity;
import com.knowflow.application.document.repository.DocCommentRepository;
import com.knowflow.application.document.repository.DocumentRepository;
import com.knowflow.application.exception.BusinessException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 文档协同评论用例服务 支持根评论发表、楼中楼嵌套回复、点赞、权限删除与递归树渲染 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocCommentService {

  /** Redis Key 模板：评论点赞去重，24h 内每用户只能点赞一次。kf:comment:like:{commentId}:{userId} */
  private static final String COMMENT_LIKE_KEY = "kf:comment:like:%d:%d";

  /** 点赞防刷 TTL：24 小时 */
  private static final long COMMENT_LIKE_TTL_HOURS = 24L;

  private final DocumentRepository documentRepository;
  private final DocCommentRepository docCommentRepository;
  private final DocCommentMapper docCommentMapper;
  private final StringRedisTemplate stringRedisTemplate;
  private final com.knowflow.application.document.acl.DocAccessControlService
      docAccessControlService;

  /** 发表根评论 */
  @Transactional
  public DocCommentDTO addRootComment(
      Long workSpaceId, Long documentId, Long userId, String content) {
    DocumentEntity doc =
        documentRepository
            .findById(documentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.Document.DOC_NOT_FOUND, "目标文档不存在"));

    docAccessControlService.assertCanRead(doc, userId);

    DocCommentEntity comment =
        DocCommentEntity.createRoot(doc.getWorkSpaceId(), doc.getId(), userId, content);
    DocCommentEntity saved = docCommentRepository.save(comment);

    log.info(
        "【DocCommentService】根评论发表成功: commentId={}, docId={}, userId={}",
        saved.getId(),
        doc.getId(),
        userId);
    return docCommentMapper.toDTO(saved);
  }

  /** 发表楼中楼回复 */
  @Transactional
  public DocCommentDTO addReplyComment(
      Long workSpaceId,
      Long documentId,
      Long userId,
      Long parentId,
      Long replyToUserId,
      String content) {
    DocumentEntity doc =
        documentRepository
            .findById(documentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.Document.DOC_NOT_FOUND, "目标文档不存在"));

    docAccessControlService.assertCanRead(doc, userId);

    DocCommentEntity parentComment =
        docCommentRepository
            .findById(parentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.Document.DOC_NOT_FOUND, "父评论不存在"));

    // 防跨文档穿透：确保父评论属于同一篇文档
    if (!parentComment.getDocumentId().equals(doc.getId())) {
      throw BusinessException.badRequest("父评论不属于当前文档");
    }

    DocCommentEntity reply =
        DocCommentEntity.createReply(
            doc.getWorkSpaceId(),
            doc.getId(),
            userId,
            parentComment.getId(),
            replyToUserId,
            content);
    DocCommentEntity saved = docCommentRepository.save(reply);

    log.info(
        "【DocCommentService】回复评论发表成功: replyId={}, parentId={}, docId={}, userId={}",
        saved.getId(),
        parentId,
        doc.getId(),
        userId);

    return docCommentMapper.toDTO(saved);
  }

  /**
   * 点赞评论（每用户每评论 24h 内最多点赞一次）
   *
   * @param commentId 评论 ID
   * @param userId 当前操作用户 ID
   * @throws BusinessException 评论不存在 / 24h 内已点赞
   */
  @Transactional
  public void likeComment(Long commentId, Long userId) {
    // 1. 校验评论是否存在
    if (!docCommentRepository.existsById(commentId)) {
      throw new BusinessException(ErrorCode.Document.DOC_NOT_FOUND, "评论不存在");
    }

    // 2. Redis 防刷：24h 内同一用户不可重复点赞
    String redisKey = String.format(COMMENT_LIKE_KEY, commentId, userId);
    Boolean isNew =
        stringRedisTemplate
            .opsForValue()
            .setIfAbsent(redisKey, "1", COMMENT_LIKE_TTL_HOURS, TimeUnit.HOURS);
    if (Boolean.FALSE.equals(isNew)) {
      throw BusinessException.badRequest("您已在 24 小时内点赞过该评论，请稍后再试");
    }

    // 3. 原子 SQL 递增，避免应用层 read-modify-write 并发丢失
    docCommentRepository.incrementLikeCount(commentId);
    log.info("【DocCommentService】评论点赞成功: commentId={}, userId={}", commentId, userId);
  }

  /** 删除评论 */
  @Transactional
  public void deleteComment(Long commentId, Long currentUserId) {
    DocCommentEntity comment =
        docCommentRepository
            .findById(commentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.Document.DOC_NOT_FOUND, "评论不存在"));

    if (!comment.getUserId().equals(currentUserId)) {
      throw new BusinessException(ErrorCode.Document.DOC_ACCESS_DENIED, "仅评论发表者可删除自己的评论");
    }

    docCommentRepository.delete(comment);
    log.info("【DocCommentService】评论已删除: commentId={}, userId={}", commentId, currentUserId);
  }

  /** 查询文档全部评论树（树形结构） */
  @Transactional(readOnly = true)
  public List<DocCommentNodeDTO> getCommentTree(Long documentId, Long userId) {
    if (userId != null) {
      DocumentEntity doc =
          documentRepository
              .findById(documentId)
              .orElseThrow(
                  () -> new BusinessException(ErrorCode.Document.DOC_NOT_FOUND, "目标文档不存在"));
      docAccessControlService.assertCanRead(doc, userId);
    }

    List<DocCommentEntity> allComments =
        docCommentRepository.findByDocumentIdOrderByCreateTimeAsc(documentId);

    if (allComments.isEmpty()) {
      return Collections.emptyList();
    }

    Map<Long, List<DocCommentEntity>> parentMap =
        allComments.stream().collect(Collectors.groupingBy(DocCommentEntity::getParentId));

    return buildCommentTreeNodes(0L, parentMap);
  }

  @Transactional(readOnly = true)
  public List<DocCommentNodeDTO> getCommentTree(Long documentId) {
    return getCommentTree(documentId, null);
  }

  private List<DocCommentNodeDTO> buildCommentTreeNodes(
      Long currentParentId, Map<Long, List<DocCommentEntity>> parentMap) {
    List<DocCommentEntity> children =
        parentMap.getOrDefault(currentParentId, Collections.emptyList());
    List<DocCommentNodeDTO> result = new ArrayList<>();

    for (DocCommentEntity comment : children) {
      List<DocCommentNodeDTO> replies = buildCommentTreeNodes(comment.getId(), parentMap);
      result.add(
          new DocCommentNodeDTO(
              comment.getId(),
              comment.getWorkSpaceId(),
              comment.getDocumentId(),
              comment.getUserId(),
              comment.getParentId(),
              comment.getReplyToUserId(),
              comment.getContent(),
              comment.getLikeCount(),
              comment.getCreateTime(),
              replies));
    }
    return result;
  }
}
