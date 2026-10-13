package com.knowflow.application.document.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowflow.application.common.ErrorCode;
import com.knowflow.application.document.acl.DocAccessControlService;
import com.knowflow.application.document.api.dto.DocVersionCompareDTO;
import com.knowflow.application.document.api.dto.DocVersionDTO;
import com.knowflow.application.document.api.dto.DocumentDTO;
import com.knowflow.application.document.mapper.DocVersionMapper;
import com.knowflow.application.document.mapper.DocumentMapper;
import com.knowflow.application.document.model.entity.DocVersionEntity;
import com.knowflow.application.document.model.entity.DocumentEntity;
import com.knowflow.application.document.model.enums.DocSourceTypeEnum;
import com.knowflow.application.document.repository.DocVersionRepository;
import com.knowflow.application.document.repository.DocumentRepository;
import com.knowflow.application.document.statemachine.DocumentStateEnum;
import com.knowflow.application.enums.WorkSpaceAclEnum;
import com.knowflow.application.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DocVersionServiceTest {

  @Mock private DocumentRepository documentRepository;
  @Mock private DocVersionRepository docVersionRepository;
  @Mock private DocVersionMapper docVersionMapper;
  @Mock private DocumentMapper documentMapper;
  @Mock private DocAccessControlService docAccessControlService;
  @Mock private ApplicationEventPublisher eventPublisher;

  private DocVersionService versionService;

  @BeforeEach
  void setUp() {
    versionService =
        new DocVersionService(
            documentRepository,
            docVersionRepository,
            docVersionMapper,
            documentMapper,
            docAccessControlService,
            eventPublisher);
  }

  @Test
  @DisplayName("创建版本快照：自动自增版本号，校验写权限，生成不可变快照记录并持久化")
  void testCreateSnapshotSuccess() {
    // Arrange
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 10L, "设计文档", "摘要", "初始正文", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 100L);

    when(documentRepository.findById(100L)).thenReturn(Optional.of(doc));
    when(docVersionRepository.save(any(DocVersionEntity.class)))
        .thenAnswer(
            inv -> {
              DocVersionEntity entity = inv.getArgument(0);
              ReflectionTestUtils.setField(entity, "id", 500L);
              return entity;
            });

    DocVersionDTO expectedDto =
        new DocVersionDTO(
            500L, 1L, 100L, 1, "v1.0", "设计文档", "初始正文", "首发快照", 99L, 100, LocalDateTime.now());
    when(docVersionMapper.toDTO(any(DocVersionEntity.class), any(DocumentStateEnum.class)))
        .thenReturn(expectedDto);

    // Act
    DocVersionDTO result = versionService.createSnapshot(100L, "v1.0", "首发快照", 99L);

    // Assert
    assertThat(result).isNotNull();
    assertThat(result.versionNumber()).isEqualTo(1);
    assertThat(result.versionTag()).isEqualTo("v1.0");
    verify(docAccessControlService).assertCanWrite(doc, 99L);
    verify(docVersionRepository).save(any(DocVersionEntity.class));
    verify(documentRepository).save(doc);
  }

  @Test
  @DisplayName("回滚历史版本：校验写权限，将文档标题与正文覆盖回退至目标版本快照状态")
  void testRollbackToVersionSuccess() {
    // Arrange
    DocumentEntity doc =
        DocumentEntity.createFromImport(
            1L, 10L, "设计文档", "摘要", "改乱了的新内容", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 100L);

    DocVersionEntity snapshot =
        DocVersionEntity.createSnapshot(1L, 100L, 1, "v1.0", "设计文档 v1.0", "最初的正确正文", "里程碑", 99L);
    ReflectionTestUtils.setField(snapshot, "id", 501L);

    when(documentRepository.findById(100L)).thenReturn(Optional.of(doc));
    when(docVersionRepository.findByDocumentIdAndVersionNumber(100L, 1))
        .thenReturn(Optional.of(snapshot));
    when(documentRepository.save(any(DocumentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

    DocumentDTO expectedDto =
        new DocumentDTO(
            100L,
            1L,
            10L,
            "设计文档 v1.0",
            "摘要",
            "最初的正确正文",
            DocumentStateEnum.PENDING_APPROVAL,
            WorkSpaceAclEnum.INTERNAL,
            DocSourceTypeEnum.IMPORT,
            null,
            0,
            "v1.0-rollback",
            null,
            6,
            0,
            0,
            99L,
            LocalDateTime.now(),
            LocalDateTime.now());
    when(documentMapper.toDTO(any(DocumentEntity.class))).thenReturn(expectedDto);

    // Act
    DocumentDTO rolledBack = versionService.rollbackToVersion(100L, 1, 99L);

    // Assert
    assertThat(rolledBack.content()).isEqualTo("最初的正确正文");
    assertThat(rolledBack.title()).isEqualTo("设计文档 v1.0");
    assertThat(rolledBack.status()).isEqualTo(DocumentStateEnum.PENDING_APPROVAL);
    verify(docAccessControlService).assertCanWrite(doc, 99L);
  }

  @Test
  @DisplayName("回滚已发布文档：触发 DocumentUpdatedEvent 领域事件以同步 ES 索引")
  void testRollbackPublishedDocument_publishesUpdatedEvent() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 10L, "设计文档", "摘要", "已发布内容", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 100L);
    ReflectionTestUtils.setField(doc, "status", DocumentStateEnum.APPROVED);

    DocVersionEntity snapshot =
        DocVersionEntity.createSnapshot(1L, 100L, 1, "v1.0", "设计文档 v1.0", "老正文", "里程碑", 99L);

    when(documentRepository.findById(100L)).thenReturn(Optional.of(doc));
    when(docVersionRepository.findByDocumentIdAndVersionNumber(100L, 1))
        .thenReturn(Optional.of(snapshot));
    when(documentRepository.save(any(DocumentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

    DocumentDTO expectedDto =
        new DocumentDTO(
            100L,
            1L,
            10L,
            "设计文档 v1.0",
            "摘要",
            "老正文",
            DocumentStateEnum.APPROVED,
            WorkSpaceAclEnum.INTERNAL,
            DocSourceTypeEnum.IMPORT,
            null,
            0,
            "v1.0",
            null,
            6,
            0,
            0,
            99L,
            LocalDateTime.now(),
            LocalDateTime.now());
    when(documentMapper.toDTO(any(DocumentEntity.class))).thenReturn(expectedDto);

    versionService.rollbackToVersion(100L, 1, 99L);

    verify(eventPublisher)
        .publishEvent(
            any(com.knowflow.application.document.statemachine.DocumentStateTransitionEvent.class));
  }

  @Test
  @DisplayName("回滚不存在的历史版本应精准抛出 DOC_VERSION_NOT_FOUND 异常")
  void testRollbackToVersionNotFound() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 10L, "设计文档", "摘要", "正文", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 100L);

    when(documentRepository.findById(100L)).thenReturn(Optional.of(doc));
    when(docVersionRepository.findByDocumentIdAndVersionNumber(100L, 999))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> versionService.rollbackToVersion(100L, 999, 10L))
        .isInstanceOf(BusinessException.class)
        .matches(
            ex ->
                ((BusinessException) ex)
                    .getCode()
                    .equals(ErrorCode.Document.DOC_VERSION_NOT_FOUND.getCode()));
  }

  @Test
  @DisplayName("提取版本对比原文：返回轻量级 DocVersionCompareDTO 供前端 Diff 渲染")
  void testCompareVersionsSuccess() {
    // Arrange
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 10L, "设计文档", "摘要", "当前草稿内容", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 100L);

    DocVersionEntity v1 =
        DocVersionEntity.createSnapshot(1L, 100L, 1, "v1.0", "设计文档", "第一版旧正文", "初始发布", 99L);

    when(documentRepository.findById(100L)).thenReturn(Optional.of(doc));
    when(docVersionRepository.findByDocumentIdAndVersionNumber(100L, 1))
        .thenReturn(Optional.of(v1));

    // Act
    DocVersionCompareDTO compareResult = versionService.compareVersions(100L, 1, null, 99L);

    // Assert
    assertThat(compareResult).isNotNull();
    assertThat(compareResult.oldVersionNumber()).isEqualTo(1);
    assertThat(compareResult.oldContent()).isEqualTo("第一版旧正文");
    assertThat(compareResult.newVersionNumber()).isEqualTo(1);
    assertThat(compareResult.newVersionTag()).isEqualTo("v1.0");
    assertThat(compareResult.newContent()).isEqualTo("当前草稿内容");
    verify(docAccessControlService).assertCanRead(doc, 99L);
  }

  @Test
  @DisplayName("查询版本列表：同一个文档仅发布版本为 PUBLISHED，其余版本回归 DRAFT 状态")
  void testListVersions_OnlyPublishedVersionHasPublishedStatus() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(
            1L, 10L, "设计文档", "摘要", "当前版本2内容", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 100L);
    doc.publish("v1.0"); // publishedVersion = 1, status = PUBLISHED
    // upgrade to v2: status reverts to DRAFT, publishedVersion becomes null
    doc.upgradeToNewVersion(2, "v2.0", "设计文档", "摘要", "内容v2", 10L, "test.pdf");
    // now publish v2: publishedVersion = 2, status = PUBLISHED
    doc.publish("v2.0");

    DocVersionEntity v1 =
        DocVersionEntity.createSnapshot(1L, 100L, 1, "v1.0", "设计文档", "内容v1", "初始", 99L);
    ReflectionTestUtils.setField(v1, "id", 501L);
    DocVersionEntity v2 =
        DocVersionEntity.createSnapshot(1L, 100L, 2, "v2.0", "设计文档", "内容v2", "升级", 99L);
    ReflectionTestUtils.setField(v2, "id", 502L);

    when(documentRepository.findById(100L)).thenReturn(Optional.of(doc));
    when(docVersionRepository.findByDocumentIdOrderByVersionNumberDesc(100L))
        .thenReturn(java.util.List.of(v2, v1));
    when(docVersionMapper.toDTO(any(DocVersionEntity.class), any(DocumentStateEnum.class)))
        .thenAnswer(
            inv -> {
              DocVersionEntity e = inv.getArgument(0);
              DocumentStateEnum status = inv.getArgument(1);
              return new DocVersionDTO(
                  e.getId(),
                  e.getWorkSpaceId(),
                  e.getDocumentId(),
                  e.getVersionNumber(),
                  e.getVersionTag(),
                  e.getTitle(),
                  e.getContent(),
                  e.getChangeSummary(),
                  e.getPublisherId(),
                  e.getWordCount(),
                  status,
                  e.getCreateTime());
            });

    java.util.List<DocVersionDTO> versions = versionService.listVersions(100L, 99L);

    assertThat(versions).hasSize(2);
    assertThat(versions.get(0).versionNumber()).isEqualTo(2);
    assertThat(versions.get(0).status()).isEqualTo(DocumentStateEnum.APPROVED);
    assertThat(versions.get(1).versionNumber()).isEqualTo(1);
    assertThat(versions.get(1).status()).isEqualTo(DocumentStateEnum.PENDING_APPROVAL);
  }

  @Test
  @DisplayName("获取版本详情：若文档未审批通过，即使曾是审批版本也统一返回 PENDING_APPROVAL 状态")
  void testGetVersion_WhenDocInDraft_AllVersionsAreDraft() {
    DocumentEntity doc =
        DocumentEntity.createFromImport(1L, 10L, "设计文档", "摘要", "内容", WorkSpaceAclEnum.INTERNAL);
    ReflectionTestUtils.setField(doc, "id", 100L);
    // doc is in PENDING_APPROVAL status, publishedVersion is null

    DocVersionEntity v1 =
        DocVersionEntity.createSnapshot(1L, 100L, 1, "v1.0", "设计文档", "内容v1", "初始", 99L);
    ReflectionTestUtils.setField(v1, "id", 501L);

    when(documentRepository.findById(100L)).thenReturn(Optional.of(doc));
    when(docVersionRepository.findByDocumentIdAndVersionNumber(100L, 1))
        .thenReturn(Optional.of(v1));
    when(docVersionMapper.toDTO(any(DocVersionEntity.class), any(DocumentStateEnum.class)))
        .thenAnswer(
            inv -> {
              DocVersionEntity e = inv.getArgument(0);
              DocumentStateEnum status = inv.getArgument(1);
              return new DocVersionDTO(
                  e.getId(),
                  e.getWorkSpaceId(),
                  e.getDocumentId(),
                  e.getVersionNumber(),
                  e.getVersionTag(),
                  e.getTitle(),
                  e.getContent(),
                  e.getChangeSummary(),
                  e.getPublisherId(),
                  e.getWordCount(),
                  status,
                  e.getCreateTime());
            });

    DocVersionDTO dto = versionService.getVersion(100L, 1, 99L);

    assertThat(dto).isNotNull();
    assertThat(dto.versionNumber()).isEqualTo(1);
    assertThat(dto.status()).isEqualTo(DocumentStateEnum.PENDING_APPROVAL);
  }
}
