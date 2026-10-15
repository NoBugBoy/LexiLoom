package io.lexiloom.application.document.service;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.common.ErrorCode;
import io.lexiloom.application.document.acl.DocAccessControlService;
import io.lexiloom.application.document.api.dto.DocParseStatusDTO;
import io.lexiloom.application.document.api.dto.DocumentDTO;
import io.lexiloom.application.document.mapper.DocumentMapper;
import io.lexiloom.application.document.model.entity.DocSourceFileEntity;
import io.lexiloom.application.document.model.entity.DocumentEntity;
import io.lexiloom.application.document.model.event.DocumentReadEvent;
import io.lexiloom.application.document.model.request.DocumentPageRequest;
import io.lexiloom.application.document.repository.DocSourceFileRepository;
import io.lexiloom.application.document.repository.DocumentRepository;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
import io.lexiloom.application.exception.BusinessException;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 文档核心查询服务 (CQRS - Query Side) 聚合文档详情拉取、异步阅读量统计、动态条件分页与 ACL 读权限过滤 */
@Slf4j
@Service
@RequiredArgsConstructor
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
    return new DocParseStatusDTO(
        doc.getId(), doc.getSourceFileId(), doc.getParseStatus(), errorMessage);
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
    if ((dto.fileName() == null || dto.fileName().equals(doc.getTitle()))
        && doc.getSourceFileId() != null) {
      String realFileName =
          docSourceFileRepository
              .findById(doc.getSourceFileId())
              .map(DocSourceFileEntity::getOriginalFileName)
              .orElse(dto.fileName());
      if (realFileName != null && !realFileName.equals(dto.fileName())) {
        dto =
            new DocumentDTO(
                dto.id(),
                dto.workSpaceId(),
                dto.categoryId(),
                dto.title(),
                dto.summary(),
                dto.content(),
                dto.status(),
                dto.parseStatus(),
                dto.visibility(),
                dto.sourceType(),
                dto.sourceFileId(),
                realFileName,
                dto.currentVersion(),
                dto.currentVersionTag(),
                dto.versionCount(),
                dto.publishedVersion(),
                dto.coverUrl(),
                dto.wordCount(),
                dto.readCount(),
                dto.likeCount(),
                dto.createBy(),
                dto.createTime(),
                dto.updateTime());
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
            .setIfAbsent(redisKey, "1", DOC_READ_TTL_HOURS, TimeUnit.HOURS);
    if (Boolean.TRUE.equals(isNew)) {
      documentRepository.incrementReadCount(event.documentId());
    }
  }

  /** 动态多条件分页检索文档列表 */
  @Transactional(readOnly = true)
  public Page<DocumentDTO> page(DocumentPageRequest request, Long userId) {
    // 0. 工作空间成员准入校验：防止跨租户越权枚举其他团队文档
    docAccessControlService.assertCanReadWorkSpace(request.workSpaceId(), userId);
    // 私密文档可见性：仅文档作者本人或团队 OWNER/ADMIN 可见
    boolean workspaceAdmin =
        docAccessControlService.isWorkspaceAdmin(request.workSpaceId(), userId);

    Specification<DocumentEntity> spec =
        (root, query, cb) -> {
          List<Predicate> predicates = new ArrayList<>();

          // 1. 所属团队空间隔离 (强约束)
          predicates.add(cb.equal(root.get("workSpaceId"), request.workSpaceId()));

          // 1.1 文档可见性隔离：非管理员仅可读取 PUBLIC / INTERNAL 以及自己创建的 PRIVATE 文档
          if (!workspaceAdmin) {
            predicates.add(
                cb.or(
                    cb.notEqual(root.get("visibility"), WorkSpaceAclEnum.PRIVATE),
                    cb.equal(root.get("createBy"), userId)));
          }

          // 2. 指定分类筛选
          if (request.categoryId() != null && request.categoryId() >= 0) {
            predicates.add(cb.equal(root.get("categoryId"), request.categoryId()));
          }

          // 3. 文档生命周期状态筛选
          if (request.status() != null) {
            predicates.add(cb.equal(root.get("status"), request.status()));
          }

          // 4. 标题与摘要模糊关键词搜索
          if (StrUtil.isNotBlank(request.keyword())) {
            String kw = "%" + request.keyword().trim().toLowerCase() + "%";
            predicates.add(
                cb.or(
                    cb.like(cb.lower(root.get("title")), kw),
                    cb.like(cb.lower(root.get("summary")), kw)));
          }

          return cb.and(predicates.toArray(new Predicate[0]));
        };

    Page<DocumentEntity> entityPage = documentRepository.findAll(spec, request.toPageable());
    return entityPage.map(documentMapper::toDTO);
  }

  /** 按分类拉取文档精简列表（补充团队权限断言） */
  @Transactional(readOnly = true)
  public List<DocumentDTO> listByCategory(Long workSpaceId, Long categoryId, Long userId) {
    docAccessControlService.assertCanReadWorkSpace(workSpaceId, userId);
    List<DocumentEntity> list =
        documentRepository.findByWorkSpaceIdAndCategoryId(workSpaceId, categoryId);
    return documentMapper.toDTOList(list);
  }
}
