package com.knowflow.application.document.service;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import com.knowflow.application.common.ErrorCode;
import com.knowflow.application.document.acl.DocAccessControlService;
import com.knowflow.application.document.api.dto.DocumentDTO;
import com.knowflow.application.document.mapper.DocumentMapper;
import com.knowflow.application.document.model.entity.DocSourceFileEntity;
import com.knowflow.application.document.model.entity.DocumentEntity;
import com.knowflow.application.document.model.event.DocumentDeletedEvent;
import com.knowflow.application.document.model.event.DocumentUpdatedEvent;
import com.knowflow.application.document.model.request.DocumentTransitionRequest;
import com.knowflow.application.document.model.request.DocumentUpdateRequest;
import com.knowflow.application.document.parser.DocParserEngineEnum;
import com.knowflow.application.document.pipeline.DocumentImportCommand;
import com.knowflow.application.document.pipeline.DocumentImportPipeline;
import com.knowflow.application.document.pipeline.DocumentImportResult;
import com.knowflow.application.document.repository.DocCategoryRepository;
import com.knowflow.application.document.repository.DocCommentRepository;
import com.knowflow.application.document.repository.DocSourceFileRepository;
import com.knowflow.application.document.repository.DocumentRepository;
import com.knowflow.application.document.statemachine.DocumentStateContext;
import com.knowflow.application.document.statemachine.DocumentStateEnum;
import com.knowflow.application.document.statemachine.DocumentStateMachineEngine;
import com.knowflow.application.enums.WorkSpaceAclEnum;
import com.knowflow.application.exception.BusinessException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** 文档核心命令服务 (CQRS - Command Side) 聚合文档创建、修改、删除、导入、状态机驱动流转与文档点赞 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentCommandService {

  /** Redis Key 模板：文档点赞去重，24h 内每用户只能点赞一次。kf:doc:like:{docId}:{userId} */
  private static final String DOC_LIKE_KEY = "kf:doc:like:%d:%d";

  private static final long DOC_LIKE_TTL_HOURS = 24L;

  /** 单文件导入大小上限：10MB（超限直接拒绝，避免整文件读入内存导致 OOM） */
  private static final long MAX_IMPORT_FILE_SIZE_BYTES = 5L * 1024 * 1024;

  private final DocumentRepository documentRepository;
  private final DocAccessControlService docAccessControlService;
  private final DocumentStateMachineEngine stateMachineEngine;
  private final DocumentImportPipeline importPipeline;
  private final DocumentMapper documentMapper;
  private final ApplicationEventPublisher eventPublisher;
  private final DocCommentRepository docCommentRepository;
  private final DocCategoryRepository docCategoryRepository;
  private final DocSourceFileRepository docSourceFileRepository;
  private final StringRedisTemplate stringRedisTemplate;

  /** 修改编辑文档内容 */
  @Transactional
  public DocumentDTO update(Long docId, DocumentUpdateRequest request, Long userId) {
    DocumentEntity doc =
        documentRepository
            .findById(docId)
            .orElseThrow(() -> new BusinessException(ErrorCode.Document.DOC_NOT_FOUND, "文档不存在"));

    docAccessControlService.assertCanWrite(doc, userId);

    doc.updateContent(request.title(), request.summary(), request.content(), null);
    if (request.categoryId() != null) {
      assertCategoryInWorkSpace(doc.getWorkSpaceId(), request.categoryId());
      doc.updateCategory(request.categoryId());
    }
    if (request.visibility() != null) {
      doc.updateVisibility(request.visibility());
    }
    if (request.coverUrl() != null) {
      doc.updateCover(request.coverUrl());
    }

    DocumentEntity updated = documentRepository.save(doc);

    // 若修改的文档已处于审批通过状态，发布更新事件以刷新 Elasticsearch 全文检索索引
    if (updated.getStatus() == DocumentStateEnum.APPROVED) {
      eventPublisher.publishEvent(
          new DocumentUpdatedEvent(docId, updated.getWorkSpaceId(), userId));
    }

    log.info("【DocumentCommandService】文档更新成功: id={}, userId={}", docId, userId);
    return documentMapper.toDTO(updated);
  }

  /** 软删除文档（级联清理关联评论与暂存草稿） */
  @Transactional
  public void delete(Long docId, Long userId) {
    DocumentEntity doc =
        documentRepository
            .findById(docId)
            .orElseThrow(() -> new BusinessException(ErrorCode.Document.DOC_NOT_FOUND, "文档不存在"));

    docAccessControlService.assertCanWrite(doc, userId);
    Long workSpaceId = doc.getWorkSpaceId();

    // 1. 软删除主文档聚合根
    documentRepository.delete(doc);

    // 2. 级联软删除关联来源文件资产（若存在）
    if (doc.getSourceFileId() != null) {
      docSourceFileRepository
          .findById(doc.getSourceFileId())
          .ifPresent(docSourceFileRepository::delete);
    }

    // 3. 级联软删除关联评论
    docCommentRepository.softDeleteByDocumentId(docId);

    // 4. 发布文档删除领域事件（触发 ES 移除索引）
    eventPublisher.publishEvent(new DocumentDeletedEvent(docId, workSpaceId, userId));
    log.info("【DocumentCommandService】文档已删除并级联清理关联数据: id={}, userId={}", docId, userId);
  }

  /** 文档点赞（每用户每文档 24h 内最多点赞一次） */
  @Transactional
  public void likeDocument(Long docId, Long userId) {
    DocumentEntity doc =
        documentRepository
            .findById(docId)
            .orElseThrow(() -> new BusinessException(ErrorCode.Document.DOC_NOT_FOUND, "文档不存在"));

    docAccessControlService.assertCanRead(doc, userId);

    String redisKey = String.format(DOC_LIKE_KEY, docId, userId);
    Boolean isNew =
        stringRedisTemplate
            .opsForValue()
            .setIfAbsent(redisKey, "1", DOC_LIKE_TTL_HOURS, TimeUnit.HOURS);
    if (!Boolean.TRUE.equals(isNew)) {
      throw new BusinessException(ErrorCode.Common.CONFLICT, "您已在 24 小时内点赞过该文档，请稍后再试");
    }

    try {
      documentRepository.incrementLikeCount(docId);
    } catch (RuntimeException e) {
      // 计数失败时回滚去重标记，避免用户 24 小时内被误锁而无法重试
      stringRedisTemplate.delete(redisKey);
      throw e;
    }
    log.info("【DocumentCommandService】文档点赞成功: docId={}, userId={}", docId, userId);
  }

  /** 文档生命周期流转 (基于 COLA StateMachine 驱动 Condition 规则校验与 Action 副作用执行) */
  @Transactional
  public DocumentDTO transitionState(Long docId, DocumentTransitionRequest request, Long userId) {
    DocumentEntity doc =
        documentRepository
            .findById(docId)
            .orElseThrow(() -> new BusinessException(ErrorCode.Document.DOC_NOT_FOUND, "文档不存在"));

    // 状态流转需具备文档可读访问权限，具体流转动作权限（审批、驳回、归档等）由状态机 Condition 统一严密校验
    docAccessControlService.assertCanRead(doc, userId);

    String operatorRole = docAccessControlService.resolveUserRole(doc.getWorkSpaceId(), userId);

    DocumentStateContext context =
        DocumentStateContext.of(
            doc.getId(),
            doc,
            userId,
            operatorRole,
            StrUtil.blankToDefault(request.reason(), "操作审批"));

    // 触发状态机：执行 Condition 规则检查，并执行对应的 Action（如发布快照生成、Redis 草稿清理等）
    DocumentStateEnum nextState =
        stateMachineEngine.fire(doc.getStatus(), request.event(), context);

    doc.transitionTo(nextState);

    DocumentEntity updated = documentRepository.save(doc);
    log.info(
        "【DocumentCommandService】文档状态流转成功: id={}, event={}, targetState={}",
        docId,
        request.event(),
        nextState);

    return documentMapper.toDTO(updated);
  }

  /** 多源文件上传导入 (支持指定已有文档升级新版本或全新导入) */
  public DocumentImportResult importFile(
      Long workSpaceId,
      Long documentId,
      Long categoryId,
      MultipartFile file,
      DocParserEngineEnum preferredEngine,
      boolean extractPdfToDoc,
      WorkSpaceAclEnum visibility,
      Long userId) {

    if (file == null || file.isEmpty()) {
      throw BusinessException.badRequest("上传文件不能为空");
    }
    if (file.getSize() > MAX_IMPORT_FILE_SIZE_BYTES) {
      throw BusinessException.badRequest("上传文件过大，单文件最大支持 100MB");
    }

    byte[] bytes;
    try (InputStream in = file.getInputStream()) {
      bytes = IoUtil.readBytes(in);
    } catch (Exception e) {
      throw BusinessException.badRequest("读取上传文件失败: " + e.getMessage());
    }

    DocumentImportCommand cmd =
        new DocumentImportCommand(
            workSpaceId,
            documentId,
            categoryId,
            DocumentImportPipeline.sanitizeFileName(file.getOriginalFilename()),
            bytes,
            file.getContentType(),
            preferredEngine,
            extractPdfToDoc,
            visibility,
            null);

    return importPipeline.importFile(cmd, userId);
  }

  /** 多源文件上传导入 (兼容旧版无 documentId 重载) */
  public DocumentImportResult importFile(
      Long workSpaceId,
      Long categoryId,
      MultipartFile file,
      DocParserEngineEnum preferredEngine,
      boolean extractPdfToDoc,
      WorkSpaceAclEnum visibility,
      Long userId) {
    return importFile(
        workSpaceId, null, categoryId, file, preferredEngine, extractPdfToDoc, visibility, userId);
  }

  /** PDF 一键提炼在线文档 */
  public DocumentDTO extractPdfToDocument(Long sourceFileId, Long targetCategoryId, Long userId) {
    DocumentEntity extractedDoc =
        importPipeline.extractPdfToDocument(sourceFileId, targetCategoryId, userId);
    return documentMapper.toDTO(extractedDoc);
  }

  /** 针对解析失败或超时的导入文档重新发起解析 */
  @Transactional
  public void retryParse(Long docId, Long userId) {
    DocumentEntity doc =
        documentRepository
            .findById(docId)
            .orElseThrow(() -> new BusinessException(ErrorCode.Document.DOC_NOT_FOUND, "文档不存在"));
    docAccessControlService.assertCanWrite(doc, userId);

    if (doc.getSourceFileId() == null) {
      throw BusinessException.badRequest("该文档非外部文件导入生成，不支持重试解析");
    }

    DocSourceFileEntity sourceFile =
        docSourceFileRepository
            .findById(doc.getSourceFileId())
            .orElseThrow(
                () -> new BusinessException(ErrorCode.Document.DOC_NOT_FOUND, "来源文件资产不存在"));

    importPipeline.retryParse(doc, sourceFile, userId);
  }

  /** 校验分类目录归属：0 表示根目录未分类，其余必须存在于同一工作空间，防止跨租户挂载分类 */
  private void assertCategoryInWorkSpace(Long workSpaceId, Long categoryId) {
    if (categoryId == null || categoryId <= 0L) {
      return;
    }
    if (!docCategoryRepository.existsByIdAndWorkSpaceId(categoryId, workSpaceId)) {
      throw BusinessException.badRequest("指定的分类目录不存在或不属于当前工作空间");
    }
  }
}
