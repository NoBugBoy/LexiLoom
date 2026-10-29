package io.lexiloom.application.document.pipeline;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.exception.ErrorCode;
import io.lexiloom.application.document.model.entity.DocSourceFileEntity;
import io.lexiloom.application.document.model.entity.DocVersionEntity;
import io.lexiloom.application.document.model.entity.DocumentEntity;
import io.lexiloom.application.document.parser.DocParseResult;
import io.lexiloom.application.document.parser.DocParseStatusEnum;
import io.lexiloom.application.document.parser.DocParserEngineEnum;
import io.lexiloom.application.document.api.DocumentContentCleaner;
import io.lexiloom.application.document.repository.DocSourceFileRepository;
import io.lexiloom.application.document.repository.DocVersionRepository;
import io.lexiloom.application.document.repository.DocumentRepository;
import io.lexiloom.application.document.api.dto.DocumentEventEnum;
import io.lexiloom.application.document.api.dto.DocumentStateEnum;
import io.lexiloom.application.document.model.event.DocumentStateTransitionEvent;
import io.lexiloom.application.exception.BusinessException;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 文档导入子事务服务 将需要事务保证的 DB 状态写入操作从编排主流程中隔离，避免 Spring self-invocation 导致 @Transactional 失效。 */
@Slf4j
@Service
public class DocumentImportTransactionalService {

  private final DocSourceFileRepository docSourceFileRepository;
  private final DocumentRepository documentRepository;
  private final DocVersionRepository docVersionRepository;
  private final org.springframework.context.ApplicationEventPublisher eventPublisher;
  private final tools.jackson.databind.ObjectMapper objectMapper;
  private final io.lexiloom.application.approval.api.ApprovalApi approvalApi;

  @org.springframework.beans.factory.annotation.Autowired
  public DocumentImportTransactionalService(
      DocSourceFileRepository docSourceFileRepository,
      DocumentRepository documentRepository,
      DocVersionRepository docVersionRepository,
      org.springframework.context.ApplicationEventPublisher eventPublisher,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          tools.jackson.databind.ObjectMapper objectMapper,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          io.lexiloom.application.approval.api.ApprovalApi approvalApi) {
    this.docSourceFileRepository = docSourceFileRepository;
    this.documentRepository = documentRepository;
    this.docVersionRepository = docVersionRepository;
    this.eventPublisher = eventPublisher;
    this.objectMapper =
        objectMapper != null ? objectMapper : new tools.jackson.databind.ObjectMapper();
    this.approvalApi = approvalApi;
  }

  public DocumentImportTransactionalService(
      DocSourceFileRepository docSourceFileRepository,
      DocumentRepository documentRepository,
      DocVersionRepository docVersionRepository,
      org.springframework.context.ApplicationEventPublisher eventPublisher) {
    this(
        docSourceFileRepository,
        documentRepository,
        docVersionRepository,
        eventPublisher,
        null,
        null);
  }

  public DocumentImportTransactionalService(
      DocSourceFileRepository docSourceFileRepository, DocumentRepository documentRepository) {
    this(docSourceFileRepository, documentRepository, null, null, null, null);
  }

  private String serializeMetadata(java.util.Map<String, Object> metadata) {
    if (metadata == null || metadata.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(metadata);
    } catch (Exception e) {
      log.warn("【DocumentImportPipeline】序列化解析元数据失败: error={}", e.getMessage());
      return null;
    }
  }

  /** 独立小事务：初始保存源文件资产 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public DocSourceFileEntity saveInitialSourceFile(
      DocumentImportCommand command, String fileHash, String storagePath, String storageUrl) {
    DocSourceFileEntity sourceFile =
        DocSourceFileEntity.create(
            command.workSpaceId(),
            null,
            command.originalFileName(),
            command.fileSize(),
            command.fileExtension(),
            command.contentType(),
            fileHash,
            storagePath,
            storageUrl);
    return docSourceFileRepository.save(sourceFile);
  }

  /** 独立小事务：主文档落库与源文件状态更新 */
  /** 独立小事务：主文档落库与源文件状态更新 (兼容重载) */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public DocumentImportResult finalizeImport(
      DocumentImportCommand command,
      Long sourceFileId,
      String storagePath,
      String storageUrl,
      boolean instantUpload,
      DocParseResult parseResult) {
    return finalizeImport(
        command, sourceFileId, storagePath, storageUrl, instantUpload, parseResult, 0L);
  }

  /** 独立小事务：主文档落库与源文件状态更新 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public DocumentImportResult finalizeImport(
      DocumentImportCommand command,
      Long sourceFileId,
      String storagePath,
      String storageUrl,
      boolean instantUpload,
      DocParseResult parseResult,
      Long operatorId) {

    DocSourceFileEntity sourceFile =
        docSourceFileRepository
            .findById(sourceFileId)
            .orElseThrow(() -> new BusinessException(ErrorCode.Document.DOC_NOT_FOUND, "来源文件记录丢失"));

    if (parseResult.isFailure()) {
      sourceFile.markParseFailed(parseResult.errorMessage());
      docSourceFileRepository.save(sourceFile);
      log.warn("【DocumentImportPipeline】文件解析失败: {}", parseResult.errorMessage());
      return DocumentImportResult.failure(
          sourceFileId,
          command.originalFileName(),
          storagePath,
          storageUrl,
          parseResult.engineUsed(),
          parseResult.errorMessage());
    }

    String fileName = DocumentImportPipeline.sanitizeFileName(command.originalFileName());
    String fallbackTitle = StrUtil.subBefore(fileName, ".", true);
    String docTitle = StrUtil.isNotBlank(fallbackTitle) ? fallbackTitle : fileName;

    if (parseResult.isQueued() || parseResult.isAsyncProcessing()) {
      DocParseStatusEnum initialStatus =
          parseResult.isQueued() ? DocParseStatusEnum.QUEUED : DocParseStatusEnum.ASYNC_PROCESSING;
      String placeholderContent = resolveAsyncPlaceholderContent(parseResult);

      DocumentEntity doc =
          DocumentEntity.createFromImport(
              command.workSpaceId(),
              command.categoryId(),
              docTitle,
              fileName,
              placeholderContent,
              sourceFileId,
              fileName,
              command.visibility(),
              initialStatus);

      DocumentEntity savedDoc = documentRepository.save(doc);
      sourceFile.bindDocument(savedDoc.getId());
      if (initialStatus == DocParseStatusEnum.QUEUED) {
        sourceFile.markQueued();
      }
      docSourceFileRepository.save(sourceFile);
      submitDocumentAuditQuietly(
          savedDoc.getId(), savedDoc.getWorkSpaceId(), operatorId, "文档导入解析完成，待审批发布");

      log.info(
          "【DocumentImportPipeline】异步解析任务已登记: docId={}, sourceFileId={}, batchId={}",
          savedDoc.getId(),
          sourceFileId,
          parseResult.metadata().get("batchId"));

      return DocumentImportResult.asyncProcessing(
          savedDoc.getId(),
          sourceFileId,
          savedDoc.getTitle(),
          fileName,
          storagePath,
          storageUrl,
          parseResult.engineUsed(),
          instantUpload,
          placeholderContent,
          parseResult.metadata());
    }

    String content = resolveSyncContentAndMarkSourceFile(sourceFile, parseResult);
    String summary = fileName;

    DocumentEntity doc =
        DocumentEntity.createFromImport(
            command.workSpaceId(),
            command.categoryId(),
            docTitle,
            summary,
            content,
            sourceFileId,
            fileName,
            command.visibility(),
            DocParseStatusEnum.SUCCESS);

    DocumentEntity savedDoc = documentRepository.save(doc);
    sourceFile.bindDocument(savedDoc.getId());
    docSourceFileRepository.save(sourceFile);
    saveSnapshotIfPresent(
        command.workSpaceId(),
        savedDoc.getId(),
        1,
        "v1.0",
        savedDoc.getTitle(),
        content,
        "初始版本导入",
        operatorId);

    return DocumentImportResult.success(
        savedDoc.getId(),
        sourceFileId,
        savedDoc.getTitle(),
        fileName,
        storagePath,
        storageUrl,
        parseResult.engineUsed(),
        instantUpload,
        content,
        parseResult.metadata());
  }

  /** 独立小事务：已有文档升级导入新版本（递增版本号，状态重置为 DRAFT，旧审批状态失效） */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public DocumentImportResult upgradeExistingDocument(
      Long existingDocId,
      DocumentImportCommand command,
      Long sourceFileId,
      String storagePath,
      String storageUrl,
      boolean instantUpload,
      DocParseResult parseResult,
      Long operatorId) {

    DocSourceFileEntity sourceFile =
        docSourceFileRepository
            .findById(sourceFileId)
            .orElseThrow(() -> new BusinessException(ErrorCode.Document.DOC_NOT_FOUND, "来源文件记录丢失"));

    DocumentEntity existingDoc =
        documentRepository
            .findById(existingDocId)
            .orElseThrow(
                () ->
                    new BusinessException(
                        ErrorCode.Document.DOC_NOT_FOUND, "目标文档不存在: " + existingDocId));

    if (parseResult.isFailure()) {
      sourceFile.markParseFailed(parseResult.errorMessage());
      docSourceFileRepository.save(sourceFile);
      log.warn("【DocumentImportPipeline】文件解析失败: {}", parseResult.errorMessage());
      return DocumentImportResult.failure(
          sourceFileId,
          command.originalFileName(),
          storagePath,
          storageUrl,
          parseResult.engineUsed(),
          parseResult.errorMessage());
    }

    String fileName = DocumentImportPipeline.sanitizeFileName(command.originalFileName());
    String fallbackTitle = StrUtil.subBefore(fileName, ".", true);
    String docTitle = StrUtil.isNotBlank(fallbackTitle) ? fallbackTitle : fileName;

    int nextVersion =
        (existingDoc.getCurrentVersion() != null ? existingDoc.getCurrentVersion() : 1) + 1;
    String nextVersionTag = "v" + nextVersion + ".0";
    DocumentStateEnum oldStatus = existingDoc.getStatus();

    if (parseResult.isQueued() || parseResult.isAsyncProcessing()) {
      DocParseStatusEnum initialStatus =
          parseResult.isQueued() ? DocParseStatusEnum.QUEUED : DocParseStatusEnum.ASYNC_PROCESSING;
      String placeholderContent = resolveAsyncPlaceholderContent(parseResult);
      existingDoc.upgradeToNewVersion(
          nextVersion,
          nextVersionTag,
          docTitle,
          fileName,
          placeholderContent,
          sourceFileId,
          fileName);
      if (initialStatus == DocParseStatusEnum.QUEUED) {
        existingDoc.markQueued();
        sourceFile.markQueued();
      } else {
        existingDoc.markAsyncProcessing();
        sourceFile.markAsyncProcessing();
      }
      DocumentEntity savedDoc = documentRepository.save(existingDoc);
      sourceFile.bindDocument(savedDoc.getId());
      docSourceFileRepository.save(sourceFile);
      submitDocumentAuditQuietly(
          savedDoc.getId(), savedDoc.getWorkSpaceId(), operatorId, "升级新版本导入，待审批发布");
      publishRevertToPendingIfApproved(oldStatus, savedDoc.getId(), operatorId, "升级新版本，旧版本回归未审批");

      return DocumentImportResult.asyncProcessing(
          savedDoc.getId(),
          sourceFileId,
          savedDoc.getTitle(),
          fileName,
          storagePath,
          storageUrl,
          parseResult.engineUsed(),
          instantUpload,
          placeholderContent,
          parseResult.metadata());
    }

    String content = resolveSyncContentAndMarkSourceFile(sourceFile, parseResult);
    existingDoc.upgradeToNewVersion(
        nextVersion, nextVersionTag, docTitle, fileName, content, sourceFileId, fileName);
    DocumentEntity savedDoc = documentRepository.save(existingDoc);
    sourceFile.bindDocument(savedDoc.getId());
    docSourceFileRepository.save(sourceFile);

    saveSnapshotIfPresent(
        command.workSpaceId(),
        savedDoc.getId(),
        nextVersion,
        nextVersionTag,
        savedDoc.getTitle(),
        content,
        "升级导入新版本",
        operatorId);
    publishRevertToPendingIfApproved(oldStatus, savedDoc.getId(), operatorId, "升级新版本，旧版本回归待审核");

    log.info(
        "【DocumentImportPipeline】文档升级新版本成功: docId={}, version={}, tag={}, title={}",
        savedDoc.getId(),
        nextVersion,
        nextVersionTag,
        savedDoc.getTitle());

    return DocumentImportResult.success(
        savedDoc.getId(),
        sourceFileId,
        savedDoc.getTitle(),
        fileName,
        storagePath,
        storageUrl,
        parseResult.engineUsed(),
        instantUpload,
        content,
        parseResult.metadata());
  }

  private String resolveAsyncPlaceholderContent(DocParseResult parseResult) {
    return parseResult.isQueued()
        ? """
        > ⏳ **[AI 智能提炼排队中]**
        > 文档已进入解析队列，等待调度解析，请稍候刷新查看最新进展。
        """
        : """
        > ⏳ **[AI 智能提炼解析中]**
        > 正在由云端大模型进行排版分析与内容提取，请稍候刷新查看最新正文。
        """;
  }

  private String resolveSyncContentAndMarkSourceFile(
      DocSourceFileEntity sourceFile, DocParseResult parseResult) {
    String rawMarkdown = StrUtil.blankToDefault(parseResult.markdownContent(), "");
    String cleaned = DocumentContentCleaner.cleanEmbeddedImages(rawMarkdown);
    sourceFile.markParseSuccess(
        parseResult.rawText(), parseResult.engineUsed(), serializeMetadata(parseResult.metadata()));
    return cleaned;
  }

  private void saveSnapshotIfPresent(
      Long workSpaceId,
      Long docId,
      int versionNumber,
      String versionTag,
      String title,
      String content,
      String summary,
      Long operatorId) {
    if (docVersionRepository == null) {
      return;
    }
    DocVersionEntity snapshot =
        DocVersionEntity.createSnapshot(
            workSpaceId,
            docId,
            versionNumber,
            versionTag,
            title,
            content,
            summary,
            operatorId != null ? operatorId : 0L);
    docVersionRepository.save(snapshot);
  }

  private void submitDocumentAuditQuietly(
      Long docId, Long workSpaceId, Long operatorId, String remark) {
    if (approvalApi == null) {
      return;
    }
    try {
      approvalApi.submitDocumentAudit(
          docId, workSpaceId, operatorId != null ? operatorId : 0L, null, remark, null);
    } catch (Exception e) {
      log.warn(
          "【DocumentImportPipeline】自动登记发布审批单异常: docId={}, error={}", docId, e.getMessage());
    }
  }

  private void publishRevertToPendingIfApproved(
      DocumentStateEnum oldStatus, Long docId, Long operatorId, String reason) {
    if (oldStatus == DocumentStateEnum.APPROVED && eventPublisher != null) {
      eventPublisher.publishEvent(
          new DocumentStateTransitionEvent(
              docId,
              oldStatus,
              DocumentStateEnum.PENDING_APPROVAL,
              DocumentEventEnum.SUBMIT,
              operatorId != null ? operatorId : 0L,
              reason,
              java.time.LocalDateTime.now()));
    }
  }

  /** 独立小事务：完成异步解析正文落库与切片元数据持久化 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void completeAsyncParse(
      Long documentId,
      Long sourceFileId,
      String markdownContent,
      String rawText,
      String suggestedTitle,
      DocParserEngineEnum engine,
      java.util.Map<String, Object> metadata) {
    String cleanedContent = DocumentContentCleaner.cleanEmbeddedImages(markdownContent);
    String cleanedRawText =
        StrUtil.isNotBlank(rawText)
            ? rawText
            : DocumentContentCleaner.extractRawText(cleanedContent);
    String metadataJson = serializeMetadata(metadata);

    documentRepository
        .findById(documentId)
        .ifPresent(
            doc -> {
              doc.markParseSuccess(cleanedContent, suggestedTitle);
              DocumentEntity savedDoc = documentRepository.save(doc);
              if (docVersionRepository != null) {
                Optional<DocVersionEntity> snapshotOpt =
                    docVersionRepository.findByDocumentIdAndVersionNumber(
                        savedDoc.getId(), savedDoc.getCurrentVersion());
                if (snapshotOpt.isEmpty()) {
                  DocVersionEntity snapshot =
                      DocVersionEntity.createSnapshot(
                          savedDoc.getWorkSpaceId(),
                          savedDoc.getId(),
                          savedDoc.getCurrentVersion(),
                          savedDoc.getCurrentVersionTag(),
                          savedDoc.getTitle(),
                          savedDoc.getContent(),
                          "异步解析完成快照",
                          savedDoc.getCreateBy() != null ? savedDoc.getCreateBy() : 0L);
                  docVersionRepository.save(snapshot);
                } else {
                  DocVersionEntity existingSnapshot = snapshotOpt.orElseThrow();
                  // 若已有快照内容为排队中或解析中的临时占位符，安全清理并重新生成真实快照
                  if (StrUtil.isBlank(existingSnapshot.getContent())
                      || existingSnapshot.getContent().startsWith("> ⏳")) {
                    docVersionRepository.delete(existingSnapshot);
                    docVersionRepository.flush();
                    DocVersionEntity snapshot =
                        DocVersionEntity.createSnapshot(
                            savedDoc.getWorkSpaceId(),
                            savedDoc.getId(),
                            savedDoc.getCurrentVersion(),
                            savedDoc.getCurrentVersionTag(),
                            savedDoc.getTitle(),
                            savedDoc.getContent(),
                            "异步解析完成快照",
                            savedDoc.getCreateBy() != null ? savedDoc.getCreateBy() : 0L);
                    docVersionRepository.save(snapshot);
                  }
                }
              }
            });

    docSourceFileRepository
        .findById(sourceFileId)
        .ifPresent(
            sourceFile -> {
              sourceFile.markParseSuccess(cleanedRawText, engine, metadataJson);
              docSourceFileRepository.save(sourceFile);
            });

    log.info(
        "【DocumentImportPipeline】异步解析完成已更新落库: docId={}, sourceFileId={}, markdownLen={},"
            + " hasMetadata={}",
        documentId,
        sourceFileId,
        markdownContent != null ? markdownContent.length() : 0,
        metadataJson != null);
  }

  /** 独立小事务：完成异步解析正文落库 (兼容 6 参数历史方法) */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void completeAsyncParse(
      Long documentId,
      Long sourceFileId,
      String markdownContent,
      String rawText,
      String suggestedTitle,
      DocParserEngineEnum engine) {
    completeAsyncParse(
        documentId, sourceFileId, markdownContent, rawText, suggestedTitle, engine, null);
  }

  /** 独立小事务：异步解析失败处理 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void failAsyncParse(Long documentId, Long sourceFileId, String errorMessage) {
    documentRepository
        .findById(documentId)
        .ifPresent(
            doc -> {
              doc.markParseFailed(errorMessage);
              documentRepository.save(doc);
            });

    docSourceFileRepository
        .findById(sourceFileId)
        .ifPresent(
            sourceFile -> {
              sourceFile.markParseFailed(errorMessage);
              docSourceFileRepository.save(sourceFile);
            });

    log.warn(
        "【DocumentImportPipeline】异步解析失败已更新落库: docId={}, sourceFileId={}, error={}",
        documentId,
        sourceFileId,
        errorMessage);
  }

  /** 独立小事务：标记文档状态由排队进入 DeepDoc 处理中 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markProcessing(Long documentId, Long sourceFileId) {
    if (documentId != null) {
      documentRepository
          .findById(documentId)
          .ifPresent(
              doc -> {
                doc.markAsyncProcessing();
                documentRepository.save(doc);
              });
    }
    if (sourceFileId != null) {
      docSourceFileRepository
          .findById(sourceFileId)
          .ifPresent(
              sourceFile -> {
                sourceFile.markAsyncProcessing();
                docSourceFileRepository.save(sourceFile);
              });
    }
    log.info("【DocumentImportPipeline】文档状态已由排队转为异步解析中: docId={}, sourceFileId={}", documentId, sourceFileId);
  }
}
