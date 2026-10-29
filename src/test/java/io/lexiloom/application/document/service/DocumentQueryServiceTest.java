package io.lexiloom.application.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.querydsl.core.types.Predicate;
import io.lexiloom.application.exception.ErrorCode;
import io.lexiloom.application.document.acl.DocAccessControlService;
import io.lexiloom.application.document.api.dto.DocParseStatusDTO;
import io.lexiloom.application.document.api.dto.DocumentDTO;
import io.lexiloom.application.document.mapper.DocumentMapper;
import io.lexiloom.application.document.model.entity.DocSourceFileEntity;
import io.lexiloom.application.document.model.entity.DocumentEntity;
import io.lexiloom.application.document.model.enums.DocSourceTypeEnum;
import io.lexiloom.application.document.model.event.DocumentReadEvent;
import io.lexiloom.application.document.model.request.DocumentPageRequest;
import io.lexiloom.application.document.parser.DocParseStatusEnum;
import io.lexiloom.application.document.repository.DocSourceFileRepository;
import io.lexiloom.application.document.repository.DocumentRepository;
import io.lexiloom.application.document.api.dto.DocumentStateEnum;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
import io.lexiloom.application.exception.BusinessException;
import java.time.Duration;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DocumentQueryServiceTest {

  @Mock private DocumentRepository documentRepository;
  @Mock private DocSourceFileRepository docSourceFileRepository;
  @Mock private DocAccessControlService docAccessControlService;
  @Mock private DocumentMapper documentMapper;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private StringRedisTemplate stringRedisTemplate;
  @Mock private ValueOperations<String, String> valueOps;

  private DocumentQueryService queryService;

  @BeforeEach
  void setUp() {
    queryService =
        new DocumentQueryService(
            documentRepository,
            docSourceFileRepository,
            docAccessControlService,
            documentMapper,
            eventPublisher,
            stringRedisTemplate);
  }

  @Test
  @DisplayName("查询解析状态：不累计阅读量，返回文档解析状态")
  void testGetParseStatusSuccess() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 0L, "异步文档", "摘要", "正文", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 100L);
    ReflectionTestUtils.setField(doc, "parseStatus", DocParseStatusEnum.ASYNC_PROCESSING);
    ReflectionTestUtils.setField(doc, "sourceFileId", 200L);

    when(documentRepository.findById(100L)).thenReturn(Optional.of(doc));

    DocSourceFileEntity sourceFile =
        DocSourceFileEntity.create(
            1L, null, "async.pdf", 1024L, "pdf", "application/pdf", "hash", "path", "url");
    ReflectionTestUtils.setField(sourceFile, "id", 200L);
    when(docSourceFileRepository.findById(200L)).thenReturn(Optional.of(sourceFile));

    DocParseStatusDTO statusDTO = queryService.getParseStatus(100L, 10L);

    assertThat(statusDTO).isNotNull();
    assertThat(statusDTO.documentId()).isEqualTo(100L);
    assertThat(statusDTO.sourceFileId()).isEqualTo(200L);
    assertThat(statusDTO.parseStatus()).isEqualTo(DocParseStatusEnum.ASYNC_PROCESSING);
    assertThat(statusDTO.errorMessage()).isNull();
    assertThat(statusDTO.queuePosition()).isEqualTo(0);

    verify(docAccessControlService).assertCanRead(doc, 10L);
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  @DisplayName("查询解析状态：排队状态返回当前排位号")
  void testGetParseStatus_Queued() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(
            1L, 0L, "排队文档", "摘要", "排队中", 201L, "queued.pdf", WorkSpaceAclEnum.INTERNAL, DocParseStatusEnum.QUEUED);
    ReflectionTestUtils.setField(doc, "id", 101L);
    ReflectionTestUtils.setField(doc, "sourceFileId", 201L);
    when(documentRepository.findById(101L)).thenReturn(Optional.of(doc));

    DocSourceFileEntity sourceFile =
        DocSourceFileEntity.create(
            1L, null, "queued.pdf", 1024L, "pdf", "application/pdf", "hash", "path", "url");
    sourceFile.markQueued();
    ReflectionTestUtils.setField(sourceFile, "id", 201L);
    when(docSourceFileRepository.findById(201L)).thenReturn(Optional.of(sourceFile));

    DocParseStatusDTO statusDTO = queryService.getParseStatus(101L, 10L);

    assertThat(statusDTO).isNotNull();
    assertThat(statusDTO.parseStatus()).isEqualTo(DocParseStatusEnum.QUEUED);
    assertThat(statusDTO.queuePosition()).isEqualTo(1);
  }

  @Test
  @DisplayName("查询解析状态：文档不存在时抛出 BusinessException")
  void testGetParseStatusNotFound() {
    when(documentRepository.findById(999L)).thenReturn(Optional.empty());

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> queryService.getParseStatus(999L, 10L))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("查阅文档详情：校验可读权限，发布异步阅读事件并返回 DTO")
  void testGetByIdSuccess() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 0L, "架构规范", "摘要", "正文", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 100L);

    when(documentRepository.findById(100L)).thenReturn(Optional.of(doc));

    DocumentDTO mockDto =
        new DocumentDTO(
            100L,
            1L,
            0L,
            "架构规范",
            "摘要",
            "正文",
            DocumentStateEnum.PENDING_APPROVAL,
            WorkSpaceAclEnum.INTERNAL,
            DocSourceTypeEnum.IMPORT,
            null,
            0,
            "v0.1-draft",
            null,
            2,
            0,
            10L,
            LocalDateTime.now(),
            LocalDateTime.now());
    when(documentMapper.toDTO(any(DocumentEntity.class))).thenReturn(mockDto);

    Optional<DocumentDTO> result = queryService.getById(100L, 10L);

    assertThat(result).isPresent();
    assertThat(result.get().title()).isEqualTo("架构规范");
    verify(docAccessControlService).assertCanRead(doc, 10L);
    verify(eventPublisher).publishEvent(any(DocumentReadEvent.class));
  }

  @Test
  @DisplayName("查阅文档详情：存在来源文件时，将 content 替换为 RustFS 访问地址以供前端原件预览")
  void testGetByIdWithSourceFileReplacesContentWithRustFsUrl() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(
            1L, 0L, "设计文档", "摘要", "未格式化的解析正文", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 19L);
    ReflectionTestUtils.setField(doc, "sourceFileId", 300L);

    when(documentRepository.findById(19L)).thenReturn(Optional.of(doc));

    DocumentDTO mockDto =
        new DocumentDTO(
            19L,
            1L,
            0L,
            "设计文档",
            "摘要",
            "未格式化的解析正文",
            DocumentStateEnum.APPROVED,
            WorkSpaceAclEnum.INTERNAL,
            DocSourceTypeEnum.IMPORT,
            null,
            0,
            "v1.0",
            null,
            1,
            0,
            10L,
            LocalDateTime.now(),
            LocalDateTime.now());
    when(documentMapper.toDTO(any(DocumentEntity.class))).thenReturn(mockDto);

    DocSourceFileEntity sourceFile =
        DocSourceFileEntity.create(
            1L,
            19L,
            "设计文档.pdf",
            2048L,
            "pdf",
            "application/pdf",
            "hash123",
            "workspaces/1/docs/hash_设计文档.pdf",
            "http://localhost:9000/lexiloom/workspaces/1/docs/hash_设计文档.pdf");
    when(docSourceFileRepository.findById(300L)).thenReturn(Optional.of(sourceFile));

    Optional<DocumentDTO> result = queryService.getById(19L, 10L);

    assertThat(result).isPresent();
    assertThat(result.get().content())
        .isEqualTo("http://localhost:9000/lexiloom/workspaces/1/docs/hash_设计文档.pdf");
    assertThat(result.get().fileName()).isEqualTo("设计文档.pdf");
  }

  @Test
  @DisplayName("阅读量去重：首次阅读（Redis 无记录）→ 原子 SQL incrementReadCount 被调用")
  void testOnDocumentRead_firstRead_incrementsCount() {
    when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.setIfAbsent(anyString(), eq("1"), eq(Duration.ofHours(24))))
        .thenReturn(Boolean.TRUE);

    queryService.onDocumentRead(new DocumentReadEvent(100L, 10L));

    verify(documentRepository).incrementReadCount(100L);
  }

  @Test
  @DisplayName("阅读量去重：24h 内重复阅读（Redis 有记录）→ incrementReadCount 不再调用")
  void testOnDocumentRead_repeatedRead_skipsIncrement() {
    when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.setIfAbsent(anyString(), eq("1"), eq(Duration.ofHours(24))))
        .thenReturn(Boolean.FALSE);

    queryService.onDocumentRead(new DocumentReadEvent(100L, 10L));

    verify(documentRepository, never()).incrementReadCount(anyLong());
  }

  @Test
  @DisplayName("阅读事件：null 事件不触发任何操作")
  void testOnDocumentRead_nullEvent_noOp() {
    queryService.onDocumentRead(null);
    verify(documentRepository, never()).incrementReadCount(anyLong());
  }

  @Test
  @DisplayName("分页检索文档：按团队空间和关键词进行过滤")
  void testPageDocumentsSuccess() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 0L, "架构规范", "摘要", "正文", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 100L);

    Page<DocumentEntity> entityPage = new PageImpl<>(List.of(doc));
    when(documentRepository.findAll(any(Predicate.class), any(Pageable.class)))
        .thenReturn(entityPage);

    DocumentDTO mockDto =
        new DocumentDTO(
            100L,
            1L,
            0L,
            "架构规范",
            "摘要",
            "正文",
            DocumentStateEnum.PENDING_APPROVAL,
            WorkSpaceAclEnum.INTERNAL,
            DocSourceTypeEnum.IMPORT,
            null,
            0,
            "v0.1-draft",
            null,
            2,
            0,
            10L,
            LocalDateTime.now(),
            LocalDateTime.now());
    when(documentMapper.toDTO(any(DocumentEntity.class))).thenReturn(mockDto);

    DocumentPageRequest req = new DocumentPageRequest(1L, null, null, "架构", 0, 10);
    Page<DocumentDTO> pageResult = queryService.page(req, 10L);

    assertThat(pageResult.getContent()).hasSize(1);
    assertThat(pageResult.getContent().get(0).title()).isEqualTo("架构规范");
    verify(docAccessControlService).assertCanReadWorkSpace(1L, 10L);
  }

  @Test
  @DisplayName("分页检索：非工作空间成员直接拒绝，避免跨租户枚举文档")
  void testPageDocumentsDeniedForNonWorkspaceMember() {
    org.mockito.Mockito.doThrow(
            new BusinessException(ErrorCode.Document.DOC_ACCESS_DENIED, "您不是该工作空间成员"))
        .when(docAccessControlService)
        .assertCanReadWorkSpace(2L, 10L);

    DocumentPageRequest req = new DocumentPageRequest(2L, null, null, null, 0, 10);

    assertThatThrownBy(() -> queryService.page(req, 10L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不是该工作空间成员");

    verify(documentRepository, never()).findAll(any(Predicate.class), any(Pageable.class));
  }

  @Test
  @DisplayName("查询解析状态：文档不存在时返回 404 语义（DOC_NOT_FOUND）")
  void testGetParseStatusNotFoundReturns404() {
    when(documentRepository.findById(999L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> queryService.getParseStatus(999L, 10L))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getHttpStatus())
        .isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("按分类检索文档列表：断言团队访问权限")
  void testListByCategorySuccess() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 5L, "分类文档", "摘要", "正文", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 102L);

    when(documentRepository.findByWorkSpaceIdAndCategoryId(1L, 5L)).thenReturn(List.of(doc));

    DocumentDTO mockDto =
        new DocumentDTO(
            102L,
            1L,
            5L,
            "分类文档",
            "摘要",
            "正文",
            DocumentStateEnum.PENDING_APPROVAL,
            WorkSpaceAclEnum.INTERNAL,
            DocSourceTypeEnum.IMPORT,
            null,
            0,
            "v0.1-draft",
            null,
            4,
            0,
            10L,
            LocalDateTime.now(),
            LocalDateTime.now());
    when(documentMapper.toDTOList(any())).thenReturn(List.of(mockDto));

    List<DocumentDTO> list = queryService.listByCategory(1L, 5L, 10L);

    assertThat(list).hasSize(1);
    verify(docAccessControlService).assertCanReadWorkSpace(1L, 10L);
  }

  @Test
  @DisplayName("查阅文档详情：正确返回来源文件名、版本总数以及当前发布版本号")
  void testGetById_ReturnsFileNameAndVersionCountAndPublishedVersion() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 0L, "开发手册", "摘要", "正文内容", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 105L);
    ReflectionTestUtils.setField(doc, "sourceFileId", 305L);
    ReflectionTestUtils.setField(doc, "currentVersion", 3);
    ReflectionTestUtils.setField(doc, "currentVersionTag", "v3.0");
    ReflectionTestUtils.setField(doc, "publishedVersion", 2);
    ReflectionTestUtils.setField(doc, "status", DocumentStateEnum.APPROVED);

    when(documentRepository.findById(105L)).thenReturn(Optional.of(doc));

    DocSourceFileEntity sourceFile =
        DocSourceFileEntity.create(
            1L, 105L, "handbook.pdf", 2048L, "pdf", "application/pdf", "dummy-hash", "path", "url");
    when(docSourceFileRepository.findById(305L)).thenReturn(Optional.of(sourceFile));

    DocumentDTO initialDto =
        new DocumentDTO(
            105L,
            1L,
            0L,
            "开发手册",
            "摘要",
            "正文内容",
            DocumentStateEnum.APPROVED,
            DocParseStatusEnum.SUCCESS,
            WorkSpaceAclEnum.INTERNAL,
            DocSourceTypeEnum.IMPORT,
            305L,
            "开发手册",
            3,
            "v3.0",
            3,
            2,
            null,
            4,
            0,
            10L,
            LocalDateTime.now(),
            LocalDateTime.now());
    when(documentMapper.toDTO(doc)).thenReturn(initialDto);

    Optional<DocumentDTO> resultOpt = queryService.getById(105L, 10L);

    assertThat(resultOpt).isPresent();
    DocumentDTO dto = resultOpt.get();
    assertThat(dto.fileName()).isEqualTo("handbook.pdf");
    assertThat(dto.versionCount()).isEqualTo(3);
    assertThat(dto.publishedVersion()).isEqualTo(2);
    assertThat(dto.currentVersion()).isEqualTo(3);
    assertThat(dto.status()).isEqualTo(DocumentStateEnum.APPROVED);
  }

  @Test
  @DisplayName("按分类检索文档列表：非管理员应过滤他人私密文档")
  void testListByCategory_FiltersPrivateDocumentsForNonAdmin() {
    DocumentEntity myPrivateDoc =
        DocumentEntity.createFromImport(1L, 5L, "我的私密文档", "摘要", "正文", WorkSpaceAclEnum.PRIVATE);
    ReflectionTestUtils.setField(myPrivateDoc, "id", 101L);
    ReflectionTestUtils.setField(myPrivateDoc, "createBy", 10L);

    DocumentEntity othersPrivateDoc =
        DocumentEntity.createFromImport(1L, 5L, "他人的私密文档", "摘要", "正文", WorkSpaceAclEnum.PRIVATE);
    ReflectionTestUtils.setField(othersPrivateDoc, "id", 102L);
    ReflectionTestUtils.setField(othersPrivateDoc, "createBy", 99L);

    when(docAccessControlService.isWorkspaceAdmin(1L, 10L)).thenReturn(false);
    when(documentRepository.findByWorkSpaceIdAndCategoryId(1L, 5L))
        .thenReturn(List.of(myPrivateDoc, othersPrivateDoc));

    DocumentDTO myDto =
        new DocumentDTO(
            101L,
            1L,
            5L,
            "我的私密文档",
            "摘要",
            "正文",
            DocumentStateEnum.APPROVED,
            WorkSpaceAclEnum.PRIVATE,
            DocSourceTypeEnum.IMPORT,
            null,
            0,
            "v1.0",
            null,
            4,
            0,
            10L,
            LocalDateTime.now(),
            LocalDateTime.now());
    when(documentMapper.toDTOList(List.of(myPrivateDoc))).thenReturn(List.of(myDto));

    List<DocumentDTO> result = queryService.listByCategory(1L, 5L, 10L);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().id()).isEqualTo(101L);
  }
}
