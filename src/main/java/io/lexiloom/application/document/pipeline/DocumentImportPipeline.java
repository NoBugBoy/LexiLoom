package io.lexiloom.application.document.pipeline;

import io.lexiloom.application.common.ErrorCode;
import io.lexiloom.application.common.storage.FileStorageGateway;
import io.lexiloom.application.document.acl.DocAccessControlService;
import io.lexiloom.application.document.model.entity.DocSourceFileEntity;
import io.lexiloom.application.document.model.entity.DocumentEntity;
import io.lexiloom.application.document.model.event.DocumentAsyncParseEvent;
import io.lexiloom.application.document.parser.DocParseResult;
import io.lexiloom.application.document.parser.DocParserEngineEnum;
import io.lexiloom.application.document.parser.DocParserGateway;
import io.lexiloom.application.document.repository.DocSourceFileRepository;
import io.lexiloom.application.document.repository.DocumentRepository;
import io.lexiloom.application.exception.BusinessException;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 多源文件导入管线编排服务 (Document Import Pipeline) 遵循“IO 操作移出事务”与“先存储后解析”工程规范： 1. 上传至 rustfs 对象存储底座并计算
 * SHA-256 (支持秒传去重)； 2. 独立小事务：来源文件资产入库 (DocSourceFileEntity)； 3. 路由至统一文档解析网关 (DocParserGateway)，仅透传
 * rustfs 存储地址与格式元数据； 4. 独立小事务：持久化生成 DocumentEntity 主文档并建立溯源双向绑定。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentImportPipeline {

  /** 上传文件名最大保留长度（超出时保留尾部，确保扩展名不丢失） */
  private static final int MAX_FILE_NAME_LENGTH = 120;

  private final FileStorageGateway fileStorageGateway;
  private final DocParserGateway docParserGateway;
  private final DocSourceFileRepository docSourceFileRepository;
  private final DocumentRepository documentRepository;
  private final DocumentImportTransactionalService transactionalService;
  private final org.springframework.context.ApplicationEventPublisher eventPublisher;
  private final DocAccessControlService docAccessControlService;

  /**
   * 核心导入入口方法 (编排入口，不加全局 @Transactional，避免长事务包裹文件 IO)
   *
   * @param command 导入命令（工作空间、文件字节、解析偏好等）
   * @param operatorId 操作人用户 ID（用于工作空间写权限校验，不可为空）
   */
  public DocumentImportResult importFile(DocumentImportCommand command, Long operatorId) {
    // 0. 越权防护：仅工作空间成员及以上角色可向该空间导入文件
    docAccessControlService.assertCanWriteWorkSpace(command.workSpaceId(), operatorId);

    // 客户端文件名不可信：剥离目录往返并剔除危险字符后再作为对象存储键使用
    String fileName = sanitizeFileName(command.originalFileName());

    log.info(
        "【DocumentImportPipeline】开始执行导入管线: workspaceId={}, fileName={}, size={}, operatorId={}",
        command.workSpaceId(),
        fileName,
        command.fileSize(),
        operatorId);

    // 1. 计算 SHA-256 哈希值 (JDK 标准库无额外依赖)
    String fileHash = calculateSha256(command.fileBytes());

    // 2. 检查是否为已有文档重复导入更新（支持指定 documentId 或通过同名文件追溯）
    DocumentEntity existingDoc = null;
    if (command.documentId() != null) {
      existingDoc =
          documentRepository
              .findByIdAndWorkSpaceId(command.documentId(), command.workSpaceId())
              .orElseThrow(
                  () -> BusinessException.notFound("指定导入的目标文档不存在: " + command.documentId()));
      docAccessControlService.assertCanWrite(existingDoc, operatorId);
    } else {
      existingDoc =
          docSourceFileRepository
              .findFirstByWorkSpaceIdAndOriginalFileNameOrderByCreateTimeDesc(
                  command.workSpaceId(), fileName)
              .map(DocSourceFileEntity::getDocumentId)
              .filter(Objects::nonNull)
              .flatMap(
                  docId -> documentRepository.findByIdAndWorkSpaceId(docId, command.workSpaceId()))
              .orElse(null);
    }

    // 3. 多次导入判定：如果同一文件 hash 相同，则命中秒传，不递增版本直接返回
    if (existingDoc != null && existingDoc.getSourceFileId() != null) {
      DocSourceFileEntity lastSourceFile =
          docSourceFileRepository.findById(existingDoc.getSourceFileId()).orElse(null);
      if (lastSourceFile != null && fileHash.equals(lastSourceFile.getFileHash())) {
        log.info(
            "【DocumentImportPipeline】上传文件哈希一致，命中秒传且无需递增版本: docId={}, hash={}",
            existingDoc.getId(),
            fileHash);
        return DocumentImportResult.success(
            existingDoc.getId(),
            lastSourceFile.getId(),
            existingDoc.getTitle(),
            fileName,
            lastSourceFile.getStoragePath(),
            lastSourceFile.getStorageUrl(),
            lastSourceFile.getParseEngine(),
            true,
            existingDoc.getContent(),
            Map.of("instantUpload", true, "versionUnchanged", true));
      }
    }

    // 4. 探测工作空间内是否存在同哈希文件（支持全局秒传与对象存储复用，避免多条记录时抛出非唯一结果异常）
    Optional<DocSourceFileEntity> existingStorageOpt =
        docSourceFileRepository.findFirstByWorkSpaceIdAndFileHashOrderByCreateTimeDesc(
            command.workSpaceId(), fileHash);

    String storagePath;
    String storageUrl;
    boolean instantUpload = false;

    if (existingStorageOpt.isPresent()) {
      storagePath = existingStorageOpt.map(DocSourceFileEntity::getStoragePath).orElseThrow();
      storageUrl = existingStorageOpt.map(DocSourceFileEntity::getStorageUrl).orElseThrow();
      instantUpload = true;
      log.info(
          "【DocumentImportPipeline】命中文件哈希秒传去重: hash={}, storagePath={}", fileHash, storagePath);
    } else {
      // 5. 上传到 rustfs 存储底座（耗时网络 IO，置于事务外）
      storagePath =
          String.format(
              "workspaces/%d/docs/%s_%s",
              command.workSpaceId(),
              fileHash.substring(0, Math.min(16, fileHash.length())),
              fileName);

      try (ByteArrayInputStream in = new ByteArrayInputStream(command.fileBytes())) {
        storageUrl =
            fileStorageGateway.upload(storagePath, in, command.contentType(), command.fileSize());
        log.info("【DocumentImportPipeline】文件成功落地 rustfs: path={}", storagePath);
      } catch (Exception e) {
        log.error("【DocumentImportPipeline】文件上传至 rustfs 失败: path={}", storagePath, e);
        throw new BusinessException(
            ErrorCode.Document.FILE_STORAGE_ERROR, "文件存储失败: " + e.getMessage());
      }
    }

    // 6. 落库保存来源文件资产初始记录 (委托独立事务服务)
    DocSourceFileEntity sourceFile =
        transactionalService.saveInitialSourceFile(command, fileHash, storagePath, storageUrl);

    // 7. 禁止同步解析，统一转入后台异步解析流水线
    DocParserEngineEnum engine =
        command.preferredEngine() != null ? command.preferredEngine() : DocParserEngineEnum.AUTO;

    DocParseResult asyncInitResult =
        DocParseResult.asyncProcessing(engine, null, null, command.extraOptions());

    // 8. 区分已有文档升级版本或新文档首次导入
    DocumentImportResult result;
    if (existingDoc != null) {
      result =
          transactionalService.upgradeExistingDocument(
              existingDoc.getId(),
              command,
              sourceFile.getId(),
              storagePath,
              storageUrl,
              instantUpload,
              asyncInitResult,
              operatorId);
    } else {
      result =
          transactionalService.finalizeImport(
              command,
              sourceFile.getId(),
              storagePath,
              storageUrl,
              instantUpload,
              asyncInitResult,
              operatorId);
    }

    // 9. 发布领域事件调度后台工作线程执行异步解析与正文提取
    eventPublisher.publishEvent(
        new DocumentAsyncParseEvent(
            result.documentId(),
            result.sourceFileId(),
            null,
            null,
            engine,
            command.originalFileName(),
            command.workSpaceId(),
            operatorId));
    log.info(
        "【DocumentImportPipeline】已发布统一异步解析领域事件: docId={}, sourceFileId={}, engine={}",
        result.documentId(),
        result.sourceFileId(),
        engine);

    return result;
  }

  /** 针对解析失败或超时的文档，基于已有对象存储文件重新发起解析 */
  public void retryParse(DocumentEntity doc, DocSourceFileEntity sourceFile, Long operatorId) {
    log.info(
        "【DocumentImportPipeline】重新触发文档异步解析: docId={}, sourceFileId={}, file={}",
        doc.getId(),
        sourceFile.getId(),
        sourceFile.getOriginalFileName());

    doc.markAsyncProcessing();
    sourceFile.markAsyncProcessing();
    documentRepository.save(doc);
    docSourceFileRepository.save(sourceFile);

    eventPublisher.publishEvent(
        new DocumentAsyncParseEvent(
            doc.getId(),
            sourceFile.getId(),
            null,
            null,
            sourceFile.getParseEngine() != null
                ? sourceFile.getParseEngine()
                : DocParserEngineEnum.AUTO,
            sourceFile.getOriginalFileName(),
            doc.getWorkSpaceId(),
            operatorId));

    log.info(
        "【DocumentImportPipeline】重试已发布异步解析领域事件: docId={}, sourceFileId={}",
        doc.getId(),
        sourceFile.getId());
  }

  /**
   * 安全化客户端上传的文件名： 1. 归一化 Windows 反斜杠并剥离全部目录部分，防止 {@code ../} 目录穿越； 2. 剔除控制字符与路径/保留字符，避免污染对象存储键与日志；
   * 3. 限制长度（保留尾部以保住扩展名），空名兜底为 {@code unnamed}。
   */
  public static String sanitizeFileName(String originalFileName) {
    if (originalFileName == null) {
      return "unnamed";
    }
    String normalized = originalFileName.replace('\\', '/');
    int lastSlash = normalized.lastIndexOf('/');
    String name = (lastSlash >= 0) ? normalized.substring(lastSlash + 1) : normalized;
    name = name.replaceAll("\\p{Cntrl}", "").replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
      return "unnamed";
    }
    return (name.length() > MAX_FILE_NAME_LENGTH)
        ? name.substring(name.length() - MAX_FILE_NAME_LENGTH)
        : name;
  }

  static String calculateSha256(byte[] bytes) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(bytes);
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm not available", e);
    }
  }
}
