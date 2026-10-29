package io.lexiloom.application.document.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.lexiloom.application.document.api.dto.DocCategoryDTO;
import io.lexiloom.application.document.api.dto.DocCategoryNodeDTO;
import io.lexiloom.application.document.mapper.DocCategoryMapper;
import io.lexiloom.application.document.model.entity.DocCategoryEntity;
import io.lexiloom.application.document.repository.DocCategoryRepository;
import io.lexiloom.application.document.repository.DocumentRepository;
import io.lexiloom.application.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DocCategoryServiceTest {

  @Mock private DocCategoryRepository docCategoryRepository;
  @Mock private DocumentRepository documentRepository;
  @Mock private DocCategoryMapper docCategoryMapper;

  private DocCategoryService categoryService;

  @BeforeEach
  void setUp() {
    categoryService =
        new DocCategoryService(docCategoryRepository, documentRepository, docCategoryMapper);
  }

  @Test
  @DisplayName("创建分类：根节点物化路径初始化为 /0/{id}/")
  void testCreateRootCategorySuccess() {
    when(docCategoryRepository.existsByWorkSpaceIdAndParentIdAndName(1L, 0L, "后端技术"))
        .thenReturn(false);

    when(docCategoryRepository.save(any(DocCategoryEntity.class)))
        .thenAnswer(
            inv -> {
              DocCategoryEntity entity = inv.getArgument(0);
              if (entity.getId() == null) {
                ReflectionTestUtils.setField(entity, "id", 10L);
              }
              return entity;
            });

    DocCategoryDTO mockDto =
        new DocCategoryDTO(10L, 1L, 0L, "后端技术", 1, 1, "/0/10/", LocalDateTime.now());
    when(docCategoryMapper.toDTO(any(DocCategoryEntity.class))).thenReturn(mockDto);

    DocCategoryDTO result = categoryService.createCategory(1L, 0L, "后端技术", 1);

    assertThat(result).isNotNull();
    assertThat(result.id()).isEqualTo(10L);
    assertThat(result.path()).isEqualTo("/0/10/");
  }

  @Test
  @DisplayName("移动分类：禁止将分类移动至自身或自身子目录下")
  void testMoveCategoryToSelfOrDescendantFails() {
    DocCategoryEntity parent = DocCategoryEntity.create(1L, 0L, "根目录", 1, "/0/", 0);
    ReflectionTestUtils.setField(parent, "id", 10L);
    ReflectionTestUtils.setField(parent, "path", "/0/10/");

    when(docCategoryRepository.findById(10L)).thenReturn(Optional.of(parent));

    // 尝试移动到自身
    assertThatThrownBy(() -> categoryService.moveCategory(10L, 10L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不能将分类移动到自己内部");

    // 尝试移动到子目录
    DocCategoryEntity child = DocCategoryEntity.create(1L, 10L, "子目录", 1, "/0/10/", 1);
    ReflectionTestUtils.setField(child, "id", 20L);
    ReflectionTestUtils.setField(child, "path", "/0/10/20/");

    when(docCategoryRepository.findById(20L)).thenReturn(Optional.of(child));

    assertThatThrownBy(() -> categoryService.moveCategory(10L, 20L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不能将分类移动到自身的子分类下");
  }

  @Test
  @DisplayName("组装分类树：递归组装各节点及子节点并统计文档数量")
  void testGetCategoryTreeSuccess() {
    DocCategoryEntity c1 = DocCategoryEntity.create(1L, 0L, "根分类", 1, "/0/", 0);
    ReflectionTestUtils.setField(c1, "id", 100L);

    DocCategoryEntity c2 = DocCategoryEntity.create(1L, 100L, "子分类", 1, "/0/100/", 1);
    ReflectionTestUtils.setField(c2, "id", 101L);

    when(docCategoryRepository.findByWorkSpaceIdOrderBySortOrderAsc(1L))
        .thenReturn(List.of(c1, c2));
    when(documentRepository.countGroupByCategoryId(1L))
        .thenReturn(List.<Object[]>of(new Object[] {100L, 5L}, new Object[] {101L, 3L}));

    List<DocCategoryNodeDTO> tree = categoryService.getCategoryTree(1L);

    assertThat(tree).hasSize(1);
    DocCategoryNodeDTO root = tree.get(0);
    assertThat(root.id()).isEqualTo(100L);
    assertThat(root.docCount()).isEqualTo(5L);
    assertThat(root.children()).hasSize(1);

    DocCategoryNodeDTO child = root.children().get(0);
    assertThat(child.id()).isEqualTo(101L);
    assertThat(child.docCount()).isEqualTo(3L);
  }

  @Test
  @DisplayName("移动分类：禁止跨团队空间移动分类")
  void testMoveCategory_crossWorkspace_fails() {
    DocCategoryEntity catSpace1 = DocCategoryEntity.create(1L, 0L, "空间1分类", 1, "/0/", 0);
    ReflectionTestUtils.setField(catSpace1, "id", 10L);

    DocCategoryEntity catSpace2 = DocCategoryEntity.create(2L, 0L, "空间2分类", 1, "/0/", 0);
    ReflectionTestUtils.setField(catSpace2, "id", 20L);

    when(docCategoryRepository.findById(10L)).thenReturn(Optional.of(catSpace1));
    when(docCategoryRepository.findById(20L)).thenReturn(Optional.of(catSpace2));

    assertThatThrownBy(() -> categoryService.moveCategory(10L, 20L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不能跨团队空间移动分类");
  }

  @Test
  @DisplayName("创建分类：父分类层级达到 10 层时禁止继续嵌套创建")
  void testCreateCategory_exceedMaxLevel_fails() {
    DocCategoryEntity deepParent =
        DocCategoryEntity.create(1L, 99L, "第10层目录", 1, "/0/1/2/3/4/5/6/7/8/9/99/", 10);
    ReflectionTestUtils.setField(deepParent, "id", 99L);

    when(docCategoryRepository.existsByWorkSpaceIdAndParentIdAndName(1L, 99L, "第11层目录"))
        .thenReturn(false);
    when(docCategoryRepository.findById(99L)).thenReturn(Optional.of(deepParent));

    assertThatThrownBy(() -> categoryService.createCategory(1L, 99L, "第11层目录", 1))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("最多支持 10 层");
  }

  @Test
  @DisplayName("移动分类：级联刷新子孙节点的物化路径与深度，且不受同尾数 ID 干扰")
  void testMoveCategoryUpdatesSubtreePathAndLevelCorrectly() {
    DocCategoryEntity targetParent = DocCategoryEntity.create(1L, 0L, "新父目录", 1, "/0/", 0);
    ReflectionTestUtils.setField(targetParent, "id", 12L);
    ReflectionTestUtils.setField(targetParent, "path", "/0/12/");
    ReflectionTestUtils.setField(targetParent, "level", 1);

    DocCategoryEntity movingCat = DocCategoryEntity.create(1L, 0L, "待移动目录", 1, "/0/", 0);
    ReflectionTestUtils.setField(movingCat, "id", 5L);
    ReflectionTestUtils.setField(movingCat, "path", "/0/5/");
    ReflectionTestUtils.setField(movingCat, "level", 1);

    // 子节点 ID 为 2（与祖先 ID 12 具有相同尾字符 '2'，验证不会误截断）
    DocCategoryEntity childCat = DocCategoryEntity.create(1L, 5L, "子目录", 1, "/0/5/", 1);
    ReflectionTestUtils.setField(childCat, "id", 2L);
    ReflectionTestUtils.setField(childCat, "path", "/0/5/2/");
    ReflectionTestUtils.setField(childCat, "level", 2);

    when(docCategoryRepository.findById(5L)).thenReturn(Optional.of(movingCat));
    when(docCategoryRepository.findById(12L)).thenReturn(Optional.of(targetParent));
    when(docCategoryRepository.save(any(DocCategoryEntity.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(docCategoryRepository.findByWorkSpaceIdAndPathStartingWith(1L, "/0/5/"))
        .thenReturn(List.of(movingCat, childCat));

    categoryService.moveCategory(5L, 12L);

    assertThat(movingCat.getPath()).isEqualTo("/0/12/5/");
    assertThat(movingCat.getLevel()).isEqualTo(2);
    assertThat(childCat.getPath()).isEqualTo("/0/12/5/2/");
    assertThat(childCat.getLevel()).isEqualTo(3);
  }

  @Test
  @DisplayName("创建分类：禁止指定其他工作空间的父分类")
  void testCreateCategoryCrossWorkspaceParentFails() {
    DocCategoryEntity foreignParent = DocCategoryEntity.create(2L, 0L, "其他空间目录", 1, "/0/", 0);
    ReflectionTestUtils.setField(foreignParent, "id", 88L);

    when(docCategoryRepository.existsByWorkSpaceIdAndParentIdAndName(1L, 88L, "子目录"))
        .thenReturn(false);
    when(docCategoryRepository.findById(88L)).thenReturn(Optional.of(foreignParent));

    assertThatThrownBy(() -> categoryService.createCategory(1L, 88L, "子目录", 1))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不能跨团队空间创建子分类");
  }
}
