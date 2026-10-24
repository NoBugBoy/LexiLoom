package io.lexiloom.application.document.model.entity;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.common.BaseEntity;
import io.lexiloom.application.document.parser.DocParseStatusEnum;
import io.lexiloom.application.document.parser.DocParserEngineEnum;
import io.lexiloom.application.exception.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 来源原始文件资产实体 记录上传到 rustfs 的原始文件（Word/PDF/附件等）元数据与哈希索引， 支撑后续 Elasticsearch 全文检索与 RAG
 * 向量双路召回时追溯并调出原始文件。
 */
@Entity
@Table(
    name = "ll_doc_source_file",
    comment = "来源文件资产表",
    indexes = {
      @Index(name = "idx_source_workspace", columnList = "work_space_id"),
      @Index(name = "idx_source_document", columnList = "document_id"),
      @Index(name = "idx_source_file_hash", columnList = "file_hash")
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocSourceFileEntity extends BaseEntity {

  /** 所属团队空间 ID */
  @Column(name = "work_space_id", comment = "所属团队空间ID", nullable = false)
  private Long workSpaceId;

  /** 关联的主文档 ID (可为空，如独立上传的附件或未转化为文档的资产) */
  @Column(name = "document_id", comment = "关联的主文档ID")
  private Long documentId;

  /** 原始文件名 (如: 需求规格说明书.docx) */
  @Column(name = "original_file_name", comment = "原始文件名", nullable = false, length = 255)
  private String originalFileName;

  /** 文件大小 (字节) */
  @Column(name = "file_size", comment = "文件大小(字节)", nullable = false)
  private Long fileSize;

  /** 文件扩展名 (如 docx, pdf) */
  @Column(name = "file_ext", comment = "文件扩展名", nullable = false, length = 20)
  private String fileExt;

  /** MIME 类型 */
  @Column(name = "mime_type", comment = "MIME类型", length = 100)
  private String mimeType;

  /** 文件 SHA-256 哈希值 (用于秒传寻址、内容去重及完整性校验) */
  @Column(name = "file_hash", comment = "文件SHA-256哈希值", nullable = false, length = 64)
  private String fileHash;

  /** rustfs 对象存储相对路径 */
  @Column(name = "storage_path", comment = "rustfs对象存储相对路径", nullable = false, length = 500)
  private String storagePath;

  /** 访问 URL */
  @Column(name = "storage_url", comment = "访问URL", length = 500)
  private String storageUrl;

  /** 解析状态 */
  @Enumerated(EnumType.STRING)
  @Column(name = "parse_status", comment = "解析状态", nullable = false, length = 30)
  private DocParseStatusEnum parseStatus;

  /** 实际使用的解析引擎 */
  @Enumerated(EnumType.STRING)
  @Column(name = "parse_engine", comment = "实际使用的解析引擎", length = 30)
  private DocParserEngineEnum parseEngine;

  /** 提取的纯正文内容 (供后续混合检索准确定位原文件) */
  @Column(name = "raw_text", comment = "提取纯正文内容", columnDefinition = "TEXT")
  private String rawText;

  /** 解析元数据与结构化切片 JSON (存储 DeepDoc 返回的 chunks 列表、坐标等完整版面元数据) */
  @Column(name = "parse_metadata", comment = "解析元数据与结构化切片JSON", columnDefinition = "TEXT")
  private String parseMetadata;

  /** 错误信息 (若解析失败) */
  @Column(name = "error_message", comment = "解析失败错误信息", length = 1000)
  private String errorMessage;

  /** 静态工厂：创建原始文件记录 */
  public static DocSourceFileEntity create(
      Long workSpaceId,
      Long documentId,
      String originalFileName,
      Long fileSize,
      String fileExt,
      String mimeType,
      String fileHash,
      String storagePath,
      String storageUrl) {
    Objects.requireNonNull(workSpaceId, "工作区ID不能为空");
    if (StrUtil.isBlank(originalFileName)) {
      throw BusinessException.badRequest("原始文件名不能为空");
    }
    if (StrUtil.isBlank(fileHash)) {
      throw BusinessException.badRequest("文件哈希不能为空");
    }
    if (StrUtil.isBlank(storagePath)) {
      throw BusinessException.badRequest("存储路径不能为空");
    }

    DocSourceFileEntity entity = new DocSourceFileEntity();
    entity.workSpaceId = workSpaceId;
    entity.documentId = documentId;
    entity.originalFileName = originalFileName.trim();
    entity.fileSize = (fileSize == null || fileSize < 0) ? 0L : fileSize;
    entity.fileExt = StrUtil.blankToDefault(fileExt, "").toLowerCase().replace(".", "");
    entity.mimeType = mimeType;
    entity.fileHash = fileHash.trim();
    entity.storagePath = storagePath.trim();
    entity.storageUrl = storageUrl;
    entity.parseStatus = DocParseStatusEnum.ASYNC_PROCESSING;
    entity.parseEngine = DocParserEngineEnum.AUTO;
    entity.rawText = null;
    entity.parseMetadata = null;
    entity.errorMessage = null;
    return entity;
  }

  /** 关联主文档 */
  public void bindDocument(Long documentId) {
    Objects.requireNonNull(documentId, "文档ID不能为空");
    this.documentId = documentId;
  }

  /** 标记解析成功 (包含完整切片元数据) */
  public void markParseSuccess(String rawText, DocParserEngineEnum engine, String parseMetadata) {
    applyParseResult(DocParseStatusEnum.SUCCESS, rawText, engine, parseMetadata);
  }

  /** 标记解析成功 */
  public void markParseSuccess(String rawText, DocParserEngineEnum engine) {
    markParseSuccess(rawText, engine, null);
  }

  /** 标记为固化版面仅预览（如 PDF 双轨制） */
  public void markPreviewOnly(String rawText, DocParserEngineEnum engine, String parseMetadata) {
    applyParseResult(DocParseStatusEnum.PREVIEW_ONLY, rawText, engine, parseMetadata);
  }

  /** 标记为固化版面仅预览 */
  public void markPreviewOnly(String rawText, DocParserEngineEnum engine) {
    markPreviewOnly(rawText, engine, null);
  }

  /** 标记解析失败 */
  public void markParseFailed(String errorMessage) {
    if (this.parseStatus == DocParseStatusEnum.SUCCESS
        || this.parseStatus == DocParseStatusEnum.PREVIEW_ONLY) {
      throw BusinessException.badRequest("文档已成功解析，不可标记为失败状态");
    }
    this.parseStatus = DocParseStatusEnum.FAILED;
    this.errorMessage = errorMessage;
  }

  /** 标记为排队等待中状态 */
  public void markQueued() {
    this.parseStatus = DocParseStatusEnum.QUEUED;
    this.errorMessage = null;
  }

  /** 重置为异步解析中状态 (供重试解析使用) */
  public void markAsyncProcessing() {
    this.parseStatus = DocParseStatusEnum.ASYNC_PROCESSING;
    this.errorMessage = null;
  }

  private void applyParseResult(
      DocParseStatusEnum newStatus,
      String rawText,
      DocParserEngineEnum engine,
      String parseMetadata) {
    this.parseStatus = newStatus;
    this.rawText = rawText;
    if (engine != null) {
      this.parseEngine = engine;
    }
    this.parseMetadata = parseMetadata;
    this.errorMessage = null;
  }
}
