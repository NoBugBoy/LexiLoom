package io.lexiloom.application.document.pipeline;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.common.ErrorCode;
import io.lexiloom.application.document.model.entity.DocSourceFileEntity;
import io.lexiloom.application.document.model.entity.DocVersionEntity;
import io.lexiloom.application.document.model.entity.DocumentEntity;
import io.lexiloom.application.document.parser.DocParseResult;
import io.lexiloom.application.document.parser.DocParseStatusEnum;
import io.lexiloom.application.document.parser.DocParserEngineEnum;
import io.lexiloom.application.document.parser.DocumentContentCleaner;
import io.lexiloom.application.document.repository.DocSourceFileRepository;
import io.lexiloom.application.document.repository.DocVersionRepository;
import io.lexiloom.application.document.repository.DocumentRepository;
import io.lexiloom.application.document.statemachine.DocumentEventEnum;
import io.lexiloom.application.document.statemachine.DocumentStateEnum;
import io.lexiloom.application.document.statemachine.DocumentStateTransitionEvent;
import io.lexiloom.application.exception.BusinessException;
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
    this(docSourceFileRepository, documentRepository, docVersionRepository, eventPublisher, null, null);
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

    if (parseResult.isAsyncProcessing()) {
      // 异步解析处理中（后台排队解析）
      String placeholderContent =
          """
          > ⏳ **[AI 智能提炼解析中]**
          > 正在由云端大模型进行排版分析与内容提取，请稍候刷新查看最新正文。
          """;

      DocumentEntity doc =
          DocumentEntity.createFromImport(
              command.workSpaceId(),
              command.categoryId(),
              docTitle,
              "导入自外部文件: " + fileName + " (AI智能解析中)",
              placeholderContent,
              sourceFileId,
              fileName,
              command.visibility(),
              DocParseStatusEnum.ASYNC_PROCESSING);

      DocumentEntity savedDoc = documentRepository.save(doc);
      sourceFile.bindDocument(savedDoc.getId());
      docSourceFileRepository.save(sourceFile);

      if (approvalApi != null) {
        try {
          approvalApi.submitDocumentAudit(
              savedDoc.getId(),
              savedDoc.getWorkSpaceId(),
              operatorId != null ? operatorId : 0L,
              null,
              "文档导入解析完成，待审批发布",
              null);
        } catch (Exception e) {
          log.warn("【DocumentImportPipeline】自动登记发布审批单异常: docId={}, error={}", savedDoc.getId(), e.getMessage());
        }
      }

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

    if (parseResult.isPreviewOnly()) {
      // 固化版面类 (PDF 默认双轨制 - 优先提取真实解析内容，避免占位符覆盖真实正文)
      String defaultPreview =
          """
          > 📄 **[PDF 固化版面原件预览模式]**
          > 本文档为高精度版面保真原件，已安全存储于知识库底层 rustfs 对象存储中。
          > 支持直接在阅读器中进行翻页预览；如需深度编辑与段落重排，请点击上方【一键 AI 提炼为文档】。
          """;
      String actualContent =
          StrUtil.isNotBlank(parseResult.markdownContent())
              ? parseResult.markdownContent()
              : StrUtil.blankToDefault(parseResult.rawText(), defaultPreview);
      String previewContent = DocumentContentCleaner.cleanEmbeddedImages(actualContent);

      DocumentEntity doc =
          DocumentEntity.createFromImport(
              command.workSpaceId(),
              command.categoryId(),
              docTitle,
              "PDF 固化版面原件预览",
              previewContent,
              sourceFileId,
              fileName,
              command.visibility(),
              DocParseStatusEnum.PREVIEW_ONLY);

      DocumentEntity savedDoc = documentRepository.save(doc);
      sourceFile.bindDocument(savedDoc.getId());
      sourceFile.markPreviewOnly(
          parseResult.rawText(),
          parseResult.engineUsed(),
          serializeMetadata(parseResult.metadata()));
      docSourceFileRepository.save(sourceFile);

      // 创建初始版本不可变快照 (v1.0)
      if (docVersionRepository != null) {
        DocVersionEntity snapshot =
            DocVersionEntity.createSnapshot(
                command.workSpaceId(),
                savedDoc.getId(),
                1,
                "v1.0",
                savedDoc.getTitle(),
                previewContent,
                "初始版本导入",
                operatorId != null ? operatorId : 0L);
        docVersionRepository.save(snapshot);
      }

      log.info(
          "【DocumentImportPipeline】PDF 原件预览文档创建完成: docId={}, title={}",
          savedDoc.getId(),
          savedDoc.getTitle());

      return DocumentImportResult.previewOnly(
          savedDoc.getId(),
          sourceFileId,
          savedDoc.getTitle(),
          fileName,
          storagePath,
          storageUrl,
          parseResult.engineUsed(),
          instantUpload,
          previewContent,
          parseResult.metadata());
    } else {
      // 纯文本类 (Markdown/TXT) 与办公文档类 (Word/DOCX) -> 100% 自由编辑
      String rawMarkdown = StrUtil.blankToDefault(parseResult.markdownContent(), "");
      String markdownContent = DocumentContentCleaner.cleanEmbeddedImages(rawMarkdown);
      String summary = "导入自外部文件: " + fileName;

      DocumentEntity doc =
          DocumentEntity.createFromImport(
              command.workSpaceId(),
              command.categoryId(),
              docTitle,
              summary,
              markdownContent,
              sourceFileId,
              fileName,
              command.visibility(),
              DocParseStatusEnum.SUCCESS);

      DocumentEntity savedDoc = documentRepository.save(doc);
      sourceFile.bindDocument(savedDoc.getId());
      sourceFile.markParseSuccess(
          parseResult.rawText(),
          parseResult.engineUsed(),
          serializeMetadata(parseResult.metadata()));
      docSourceFileRepository.save(sourceFile);

      // 创建初始版本不可变快照 (v1.0)
      if (docVersionRepository != null) {
        DocVersionEntity snapshot =
            DocVersionEntity.createSnapshot(
                command.workSpaceId(),
                savedDoc.getId(),
                1,
                "v1.0",
                savedDoc.getTitle(),
                markdownContent,
                "初始版本导入",
                operatorId != null ? operatorId : 0L);
        docVersionRepository.save(snapshot);
      }

      log.info(
          "【DocumentImportPipeline】可编辑文档导入并创建完成: docId={}, title={}, wordCount={}",
          savedDoc.getId(),
          savedDoc.getTitle(),
          savedDoc.getWordCount());

      return DocumentImportResult.success(
          savedDoc.getId(),
          sourceFileId,
          savedDoc.getTitle(),
          fileName,
          storagePath,
          storageUrl,
          parseResult.engineUsed(),
          instantUpload,
          markdownContent,
          parseResult.metadata());
    }
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
    String summary = "升级导入外部文件: " + fileName + " (" + nextVersionTag + ")";
    DocumentStateEnum oldStatus = existingDoc.getStatus();

    if (parseResult.isAsyncProcessing()) {
      String placeholderContent =
          """
          > ⏳ **[AI 智能提炼解析中]**
          > 正在由云端大模型进行排版分析与内容提取，请稍候刷新查看最新正文。
          """;
      existingDoc.upgradeToNewVersion(
          nextVersion,
          nextVersionTag,
          docTitle,
          summary,
          placeholderContent,
          sourceFileId,
          fileName);
      existingDoc.markAsyncProcessing();
      DocumentEntity savedDoc = documentRepository.save(existingDoc);
      sourceFile.bindDocument(savedDoc.getId());
      docSourceFileRepository.save(sourceFile);

      if (approvalApi != null) {
        try {
          approvalApi.submitDocumentAudit(
              savedDoc.getId(),
              savedDoc.getWorkSpaceId(),
              operatorId != null ? operatorId : 0L,
              null,
              "升级新版本导入，待审批发布",
              null);
        } catch (Exception e) {
          log.warn("【DocumentImportPipeline】自动登记发布审批单异常: docId={}, error={}", savedDoc.getId(), e.getMessage());
        }
      }

      if (oldStatus == DocumentStateEnum.APPROVED && eventPublisher != null) {
        eventPublisher.publishEvent(
            new DocumentStateTransitionEvent(
                savedDoc.getId(),
                oldStatus,
                DocumentStateEnum.PENDING_APPROVAL,
                DocumentEventEnum.SUBMIT,
                operatorId != null ? operatorId : 0L,
                "升级新版本，旧版本回归未审批",
                java.time.LocalDateTime.now()));
      }

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

    String content;
    if (parseResult.isPreviewOnly()) {
      String defaultPreview =
          """
          > 📄 **[PDF 固化版面原件预览模式]**
          > 本文档为高精度版面保真原件，已安全存储于知识库底层 rustfs 对象存储中。
          > 支持直接在阅读器中进行翻页预览；如需深度编辑与段落重排，请点击上方【一键 AI 提炼为文档】。
          """;
      String actualContent =
          StrUtil.isNotBlank(parseResult.markdownContent())
              ? parseResult.markdownContent()
              : StrUtil.blankToDefault(parseResult.rawText(), defaultPreview);
      content = DocumentContentCleaner.cleanEmbeddedImages(actualContent);
      sourceFile.markPreviewOnly(
          parseResult.rawText(),
          parseResult.engineUsed(),
          serializeMetadata(parseResult.metadata()));
    } else {
      String rawMarkdown = StrUtil.blankToDefault(parseResult.markdownContent(), "");
      content = DocumentContentCleaner.cleanEmbeddedImages(rawMarkdown);
      sourceFile.markParseSuccess(
          parseResult.rawText(),
          parseResult.engineUsed(),
          serializeMetadata(parseResult.metadata()));
    }

    existingDoc.upgradeToNewVersion(
        nextVersion, nextVersionTag, docTitle, summary, content, sourceFileId, fileName);
    DocumentEntity savedDoc = documentRepository.save(existingDoc);
    sourceFile.bindDocument(savedDoc.getId());
    docSourceFileRepository.save(sourceFile);

    // 创建升级版本不可变快照
    if (docVersionRepository != null) {
      DocVersionEntity snapshot =
          DocVersionEntity.createSnapshot(
              command.workSpaceId(),
              savedDoc.getId(),
              nextVersion,
              nextVersionTag,
              savedDoc.getTitle(),
              content,
              "升级导入新版本",
              operatorId != null ? operatorId : 0L);
      docVersionRepository.save(snapshot);
    }

    // 若此前处于审批通过状态，由于升级后回归待审核状态，发出流转事件清理旧版本 ES 索引与向量数据
    if (oldStatus == DocumentStateEnum.APPROVED && eventPublisher != null) {
      eventPublisher.publishEvent(
          new DocumentStateTransitionEvent(
              savedDoc.getId(),
              oldStatus,
              DocumentStateEnum.PENDING_APPROVAL,
              DocumentEventEnum.SUBMIT,
              operatorId != null ? operatorId : 0L,
              "升级新版本，旧版本回归待审核",
              java.time.LocalDateTime.now()));
    }

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
              if (docVersionRepository != null
                  && docVersionRepository
                      .findByDocumentIdAndVersionNumber(
                          savedDoc.getId(), savedDoc.getCurrentVersion())
                      .isEmpty()) {
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
}
