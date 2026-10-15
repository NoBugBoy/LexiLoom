package io.lexiloom.application.document.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lexiloom.application.document.acl.DocAccessControlService;
import io.lexiloom.application.document.api.dto.DocCommentDTO;
import io.lexiloom.application.document.api.dto.DocCommentNodeDTO;
import io.lexiloom.application.document.mapper.DocCommentMapper;
import io.lexiloom.application.document.model.entity.DocCommentEntity;
import io.lexiloom.application.document.model.entity.DocumentEntity;
import io.lexiloom.application.document.repository.DocCommentRepository;
import io.lexiloom.application.document.repository.DocumentRepository;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
import io.lexiloom.application.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DocCommentServiceTest {

  @Mock private DocumentRepository documentRepository;
  @Mock private DocCommentRepository docCommentRepository;
  @Mock private DocCommentMapper docCommentMapper;
  @Mock private StringRedisTemplate stringRedisTemplate;
  @Mock private ValueOperations<String, String> valueOps;

  @Mock private DocAccessControlService docAccessControlService;

  private DocCommentService commentService;

  @BeforeEach
  void setUp() {
    commentService =
        new DocCommentService(
            documentRepository,
            docCommentRepository,
            docCommentMapper,
            stringRedisTemplate,
            docAccessControlService);
  }

  @Test
  @DisplayName("发表根评论：成功创建并返回 DTO")
  void testAddRootCommentSuccess() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 0L, "文档", "摘要", "正文", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 100L);
    when(documentRepository.findById(100L)).thenReturn(Optional.of(doc));

    when(docCommentRepository.save(any(DocCommentEntity.class)))
        .thenAnswer(
            inv -> {
              DocCommentEntity entity = inv.getArgument(0);
              ReflectionTestUtils.setField(entity, "id", 50L);
              return entity;
            });

    DocCommentDTO mockDto =
        new DocCommentDTO(50L, 1L, 100L, 10L, 0L, null, "写得很清晰！", 0, LocalDateTime.now());
    when(docCommentMapper.toDTO(any(DocCommentEntity.class))).thenReturn(mockDto);

    DocCommentDTO result = commentService.addRootComment(1L, 100L, 10L, "写得很清晰！");

    assertThat(result).isNotNull();
    assertThat(result.id()).isEqualTo(50L);
    assertThat(result.parentId()).isZero();
    verify(docCommentRepository).save(any(DocCommentEntity.class));
  }

  @Test
  @DisplayName("删除评论权限校验：非发表者本人无法删除")
  void testDeleteCommentAccessDenied() {
    DocCommentEntity comment = DocCommentEntity.createRoot(1L, 100L, 10L, "评论内容");
    ReflectionTestUtils.setField(comment, "id", 50L);
    when(docCommentRepository.findById(50L)).thenReturn(Optional.of(comment));

    // 当前操作人 99L 非评论发表人 10L
    assertThatThrownBy(() -> commentService.deleteComment(50L, 99L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("仅评论发表者可删除");
  }

  @Test
  @DisplayName("组装评论树：递归组装根评论与楼中楼嵌套回复")
  void testGetCommentTreeSuccess() {
    DocCommentEntity root = DocCommentEntity.createRoot(1L, 100L, 10L, "主楼评论");
    ReflectionTestUtils.setField(root, "id", 1L);

    DocCommentEntity reply = DocCommentEntity.createReply(1L, 100L, 20L, 1L, 10L, "楼中楼回复");
    ReflectionTestUtils.setField(reply, "id", 2L);

    when(docCommentRepository.findByDocumentIdOrderByCreateTimeAsc(100L))
        .thenReturn(List.of(root, reply));

    List<DocCommentNodeDTO> tree = commentService.getCommentTree(100L);

    assertThat(tree).hasSize(1);
    DocCommentNodeDTO rootNode = tree.get(0);
    assertThat(rootNode.id()).isEqualTo(1L);
    assertThat(rootNode.replies()).hasSize(1);

    DocCommentNodeDTO replyNode = rootNode.replies().get(0);
    assertThat(replyNode.id()).isEqualTo(2L);
    assertThat(replyNode.parentId()).isEqualTo(1L);
  }

  @Test
  @DisplayName("点赞评论：首次点赞成功，Redis 写入并触发原子计数递增")
  void testLikeCommentSuccess() {
    when(docCommentRepository.existsById(50L)).thenReturn(true);
    when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.setIfAbsent(eq("ll:comment:like:50:10"), eq("1"), eq(24L), eq(TimeUnit.HOURS)))
        .thenReturn(Boolean.TRUE);

    commentService.likeComment(50L, 10L);

    verify(docCommentRepository).incrementLikeCount(50L);
  }

  @Test
  @DisplayName("点赞评论：24小时内重复点赞，抛出业务异常且不执行计数递增")
  void testLikeCommentDuplicate() {
    when(docCommentRepository.existsById(50L)).thenReturn(true);
    when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.setIfAbsent(eq("ll:comment:like:50:10"), eq("1"), eq(24L), eq(TimeUnit.HOURS)))
        .thenReturn(Boolean.FALSE);

    assertThatThrownBy(() -> commentService.likeComment(50L, 10L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("24 小时内点赞过");

    verify(docCommentRepository, never()).incrementLikeCount(anyLong());
  }

  @Test
  @DisplayName("发表回复：父评论属于其他文档时抛出参数异常，阻止孤儿评论产生")
  void testAddReplyComment_crossDocumentParent_throwsException() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 0L, "文档A", "摘要", "正文", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 100L);
    when(documentRepository.findById(100L)).thenReturn(Optional.of(doc));

    // 父评论属于文档 200L，而非当前文档 100L
    DocCommentEntity parentCommentOtherDoc = DocCommentEntity.createRoot(1L, 200L, 20L, "文档B的评论");
    ReflectionTestUtils.setField(parentCommentOtherDoc, "id", 88L);
    when(docCommentRepository.findById(88L)).thenReturn(Optional.of(parentCommentOtherDoc));

    assertThatThrownBy(() -> commentService.addReplyComment(1L, 100L, 10L, 88L, null, "恶意回复"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("父评论不属于当前文档");

    verify(docCommentRepository, never()).save(any());
  }
}
