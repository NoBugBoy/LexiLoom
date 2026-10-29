package io.lexiloom.application.document.service;

import cn.hutool.core.util.StrUtil;
import com.querydsl.core.BooleanBuilder;
import io.lexiloom.application.exception.ErrorCode;
import io.lexiloom.application.common.storage.FileStorageGateway;
import io.lexiloom.application.document.acl.DocAccessControlService;
import io.lexiloom.application.document.api.dto.DocParseStatusDTO;
import io.lexiloom.application.document.api.dto.DocumentDTO;
import io.lexiloom.application.document.mapper.DocumentMapper;
import io.lexiloom.application.document.model.entity.DocSourceFileEntity;
import io.lexiloom.application.document.model.entity.DocumentEntity;
import io.lexiloom.application.document.model.entity.QDocumentEntity;
import io.lexiloom.application.document.model.event.DocumentReadEvent;
import io.lexiloom.application.document.model.request.DocumentPageRequest;
import io.lexiloom.application.document.parser.DocParseStatusEnum;
import io.lexiloom.application.document.queue.DocParseQueueService;
import io.lexiloom.application.document.repository.DocSourceFileRepository;
import io.lexiloom.application.document.repository.DocumentRepository;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
import io.lexiloom.application.exception.BusinessException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 文档核心查询服务 (CQRS - Query Side) 聚合文档详情拉取、异步阅读量统计、动态条件分页与 ACL 读权限过滤 */
@Slf4j
@Service
public class DocumentQueryService {

  /** Redis Key 模板：阅读量去重，24h 内同一用户访问同一文档只计一次。ll:doc:read:{docId}:{userId} */
  private static final String DOC_READ_KEY = "ll:doc:read:%d:%d";

  /** 阅读去重 TTL：24 小时 */
  private static final long DOC_READ_TTL_HOURS = 24L;

  private final DocumentRepository documentRepository;
  private final DocSourceFileRepository docSourceFileRepository;
  private final DocAccessControlService docAccessControlService;
  private final DocumentMapper documentMapper;
  private final ApplicationEventPublisher eventPublisher;
  private final StringRedisTemplate stringRedisTemplate;
  private final FileStorageGateway fileStorageGateway;
  @Nullable private final DocParseQueueService docParseQueueService;

  @Autowired
  public DocumentQueryService(
      DocumentRepository documentRepository,
      DocSourceFileRepository docSourceFileRepository,
      DocAccessControlService docAccessControlService,
      DocumentMapper documentMapper,
      ApplicationEventPublisher eventPublisher,
      StringRedisTemplate stringRedisTemplate,
      @Nullable @Autowired(required = false) FileStorageGateway fileStorageGateway,
      @Nullable @Autowired(required = false) DocParseQueueService docParseQueueService) {
    this.documentRepository = documentRepository;
    this.docSourceFileRepository = docSourceFileRepository;
    this.docAccessControlService = docAccessControlService;
    this.documentMapper = documentMapper;
    this.eventPublisher = eventPublisher;
    this.stringRedisTemplate = stringRedisTemplate;
    this.fileStorageGateway = fileStorageGateway;
    this.docParseQueueService = docParseQueueService;
  }

  public DocumentQueryService(
      DocumentRepository documentRepository,
      DocSourceFileRepository docSourceFileRepository,
      DocAccessControlService docAccessControlService,
      DocumentMapper documentMapper,
      ApplicationEventPublisher eventPublisher,
      StringRedisTemplate stringRedisTemplate) {
    this(
        documentRepository,
        docSourceFileRepository,
        docAccessControlService,
        documentMapper,
        eventPublisher,
        stringRedisTemplate,
        null,
        null);
  }

  /** 查询文档解析状态（轻量级，不累计阅读量，供前端异步轮询使用） */
  @Transactional(readOnly = true)
  public DocParseStatusDTO getParseStatus(Long id, Long userId) {
    DocumentEntity doc =
        documentRepository
            .findById(id)
            .orElseThrow(
                () -> new BusinessException(ErrorCode.Document.DOC_NOT_FOUND, "文档不存在: " + id));
    docAccessControlService.assertCanRead(doc, userId);

    String errorMessage = null;
    if (doc.getSourceFileId() != null) {
      errorMessage =
          docSourceFileRepository
              .findById(doc.getSourceFileId())
              .map(DocSourceFileEntity::getErrorMessage)
              .orElse(null);
    }

    Integer queuePosition = null;
    if (doc.getParseStatus() == DocParseStatusEnum.QUEUED) {
      Integer pos = docParseQueueService != null ? docParseQueueService.getQueuePosition(doc.getId()) : null;
      queuePosition = (pos != null && pos > 0) ? pos : 1;
    } else if (doc.getParseStatus() == DocParseStatusEnum.ASYNC_PROCESSING) {
      queuePosition = 0;
    }

    return new DocParseStatusDTO(
        doc.getId(), doc.getSourceFileId(), doc.getParseStatus(), errorMessage, queuePosition);
  }

  /** 按 ID 查阅文档详情（附带读权限断言，阅读量通过异步事件解耦） */
  @Transactional(readOnly = true)
  public Optional<DocumentDTO> getById(Long id, Long userId) {
    Optional<DocumentEntity> docOpt = documentRepository.findById(id);
    if (docOpt.isEmpty()) {
      return Optional.empty();
    }

    DocumentEntity doc = docOpt.orElseThrow();
    docAccessControlService.assertCanRead(doc, userId);

    // 异步累计阅读量：通过发布领域事件解耦，避免只读查询产生主表行锁争用
    eventPublisher.publishEvent(new DocumentReadEvent(id, userId));

    DocumentDTO dto = documentMapper.toDTO(doc);
    if (doc.getSourceFileId() != null) {
      DocSourceFileEntity sourceFile =
          docSourceFileRepository.findById(doc.getSourceFileId()).orElse(null);
      if (sourceFile != null) {
        String realFileName =
            (dto.fileName() == null || dto.fileName().equals(doc.getTitle()))
                    && StrUtil.isNotBlank(sourceFile.getOriginalFileName())
                ? sourceFile.getOriginalFileName()
                : dto.fileName();

        // 获取 RustFS 中的文件访问地址：优先使用 sourceFile.getStorageUrl()，若为空则通过 FileStorageGateway 转换
        // storagePath
        String rustFsUrl =
            StrUtil.isNotBlank(sourceFile.getStorageUrl())
                ? sourceFile.getStorageUrl()
                : (fileStorageGateway != null && StrUtil.isNotBlank(sourceFile.getStoragePath())
                    ? fileStorageGateway.getUrl(sourceFile.getStoragePath())
                    : null);

        // 如果存在来源文件 RustFS 存储地址，将 content 替换为 RustFS 访问地址供前端直接预览原件；若无则保留原正文
        String effectiveContent = StrUtil.isNotBlank(rustFsUrl) ? rustFsUrl : dto.content();

        dto = dto.withSourcePreview(effectiveContent, realFileName);
      }
    }

    return Optional.of(dto);
  }

  /** 异步监听阅读事件：Redis 用户维度去重后执行原子计数更新 同一用户 24h 内重复访问同一文档不重复累加阅读量 */
  @Async
  @EventListener
  @Transactional
  public void onDocumentRead(DocumentReadEvent event) {
    if (event == null || event.documentId() == null || event.userId() == null) {
      return;
    }
    // Redis 去重：setIfAbsent 返回 false 表示 24h 内已计过该用户的阅读量
    String redisKey = String.format(DOC_READ_KEY, event.documentId(), event.userId());
    Boolean isNew =
        stringRedisTemplate
            .opsForValue()
            .setIfAbsent(redisKey, "1", Duration.ofHours(DOC_READ_TTL_HOURS));
    if (Boolean.TRUE.equals(isNew)) {
      try {
        documentRepository.incrementReadCount(event.documentId());
      } catch (Exception e) {
        stringRedisTemplate.delete(redisKey);
        log.warn("【DocumentQueryService】累计阅读量失败，清理 Redis 防重标记: docId={}, error={}", event.documentId(), e.getMessage());
      }
    }
  }

  /** 动态多条件分页检索文档列表 (QueryDSL) */
  @Transactional(readOnly = true)
  public Page<DocumentDTO> page(DocumentPageRequest request, Long userId) {
    // 0. 工作空间成员准入校验：防止跨租户越权枚举其他团队文档
    docAccessControlService.assertCanReadWorkSpace(request.workSpaceId(), userId);
    // 私密文档可见性：仅文档作者本人或团队 OWNER/ADMIN 可见
    boolean workspaceAdmin =
        docAccessControlService.isWorkspaceAdmin(request.workSpaceId(), userId);

    QDocumentEntity qDoc = QDocumentEntity.documentEntity;
    BooleanBuilder builder = new BooleanBuilder();

    // 1. 所属团队空间隔离 (强约束)
    builder.and(qDoc.workSpaceId.eq(request.workSpaceId()));

    // 1.1 文档可见性隔离：非管理员仅可读取 PUBLIC / INTERNAL 以及自己创建的 PRIVATE 文档
    if (!workspaceAdmin) {
      builder.and(qDoc.visibility.ne(WorkSpaceAclEnum.PRIVATE).or(qDoc.createBy.eq(userId)));
    }

    // 2. 指定分类筛选
    if (request.categoryId() != null && request.categoryId() >= 0) {
      builder.and(qDoc.categoryId.eq(request.categoryId()));
    }

    // 3. 文档生命周期状态筛选
    if (request.status() != null) {
      builder.and(qDoc.status.eq(request.status()));
    }

    // 4. 标题模糊关键词搜索
    if (StrUtil.isNotBlank(request.keyword())) {
      String kw = request.keyword().trim().toLowerCase();
      builder.and(qDoc.title.toLowerCase().contains(kw));
    }

    Page<DocumentEntity> entityPage = documentRepository.findAll(builder, request.toPageable());
    return entityPage.map(documentMapper::toDTO);
  }

  /** 按分类拉取文档精简列表（补充团队权限断言与私密文档隔离） */
  @Transactional(readOnly = true)
  public List<DocumentDTO> listByCategory(Long workSpaceId, Long categoryId, Long userId) {
    docAccessControlService.assertCanReadWorkSpace(workSpaceId, userId);
    boolean workspaceAdmin = docAccessControlService.isWorkspaceAdmin(workSpaceId, userId);
    List<DocumentEntity> list =
        documentRepository.findByWorkSpaceIdAndCategoryId(workSpaceId, categoryId).stream()
            .filter(doc -> doc.isVisibleToWorkspaceMember(userId, workspaceAdmin))
            .toList();
    return documentMapper.toDTOList(list);
  }
}
