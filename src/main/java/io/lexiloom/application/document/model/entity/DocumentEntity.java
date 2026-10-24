package io.lexiloom.application.document.model.entity;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.common.BaseEntity;
import io.lexiloom.application.document.model.enums.DocSourceTypeEnum;
import io.lexiloom.application.document.parser.DocParseStatusEnum;
import io.lexiloom.application.document.statemachine.DocumentStateEnum;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
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

/** 文档聚合根 (Document Aggregate Root) 知识库内容资产的核心实体，自闭合管理生命周期、版本流转与内容快照 */
@Entity
@Table(
    name = "ll_document",
    comment = "文档核心表",
    indexes = {
      @Index(name = "idx_doc_workspace", columnList = "work_space_id"),
      @Index(name = "idx_doc_category", columnList = "category_id"),
      @Index(name = "idx_doc_status", columnList = "status"),
      @Index(name = "idx_doc_create_by", columnList = "create_by")
    })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentEntity extends BaseEntity {

  /** 所属团队空间 ID */
  @Column(name = "work_space_id", comment = "所属团队空间ID", nullable = false)
  private Long workSpaceId;

  /** 所属分类树节点 ID (0 表示根目录未分类) */
  @Column(name = "category_id", comment = "所属分类树节点ID(0为根目录未分类)", nullable = false)
  private Long categoryId;

  /** 文档标题 */
  @Column(comment = "文档标题", nullable = false, length = 200)
  private String title;

  /** 文档摘要 / 前置描述 */
  @Column(comment = "文档摘要/前置描述", length = 1000)
  private String summary;

  /** Markdown 正文长文本 */
  @Column(comment = "Markdown正文内容", columnDefinition = "TEXT")
  private String content;

  /** 文档生命周期状态 */
  @Enumerated(EnumType.STRING)
  @Column(
      comment = "文档生命周期状态(DRAFT/PENDING_APPROVAL/APPROVED/REJECTED/ARCHIVED)",
      nullable = false,
      length = 30)
  private DocumentStateEnum status;

  /** 文档导入解析状态 */
  @Enumerated(EnumType.STRING)
  @Column(name = "parse_status", comment = "导入解析状态(INIT/PARSING/SUCCESS/FAILED)", length = 30)
  private DocParseStatusEnum parseStatus;

  /** 访问控制级别：公开 / 团队内部 / 私密 */
  @Enumerated(EnumType.STRING)
  @Column(comment = "访问控制级别(PUBLIC/INTERNAL/PRIVATE)", nullable = false, length = 20)
  private WorkSpaceAclEnum visibility;

  /** 内容生成/输入渠道：文件导入 / AI辅助生成 */
  @Enumerated(EnumType.STRING)
  @Column(
      name = "source_type",
      comment = "内容来源类型(IMPORT/AI_GENERATED)",
      nullable = false,
      length = 30)
  private DocSourceTypeEnum sourceType;

  /** 关联来源原始文件记录 ID (若为导入或含原件附件) */
  @Column(name = "source_file_id", comment = "关联来源原始文件记录ID")
  private Long sourceFileId;

  /** 导入来源原始文件名 (如: 需求规格说明书.docx) */
  @Column(name = "file_name", comment = "导入来源原始文件名", length = 255)
  private String fileName;

  /** 当前最新版本号序号 (1, 2, 3...) */
  @Column(name = "current_version", comment = "当前最新版本号序号", nullable = false)
  private Integer currentVersion;

  /** 当前唯一审批通过的版本号序号 (未审批通过或回归未审批时为 null) */
  @Column(name = "published_version", comment = "当前唯一审批通过的版本号序号")
  private Integer publishedVersion;

  /** 当前版本号标识 (例如 v1.0, v1.1) */
  @Column(name = "current_version_tag", comment = "当前版本号标识(如v1.0)", length = 50)
  private String currentVersionTag;

  /** 封面图 URL */
  @Column(name = "cover_url", comment = "封面图URL", length = 500)
  private String coverUrl;

  /** 统计字数 */
  @Column(name = "word_count", comment = "统计字数", nullable = false)
  private Integer wordCount;

  /** 阅读浏览量 */
  @Column(name = "read_count", comment = "阅读浏览量", nullable = false)
  private Integer readCount;

  /** 文档向量化累计消耗 Token 数 */
  @Column(name = "embedding_tokens", comment = "文档向量化累计消耗Token数", nullable = false)
  private Integer embeddingTokens = 0;

  /** 静态工厂：从外部文件导入转化创建文档（无初始源文件ID快捷重载） */
  public static DocumentEntity createFromImport(
      Long workSpaceId,
      Long categoryId,
      String title,
      String summary,
      String markdownContent,
      WorkSpaceAclEnum visibility) {
    return createFromImport(
        workSpaceId,
        categoryId,
        title,
        summary,
        markdownContent,
        null,
        visibility,
        DocParseStatusEnum.SUCCESS);
  }

  /** 静态工厂：从外部文件导入转化创建文档（全参数重载） */
  public static DocumentEntity createFromImport(
      Long workSpaceId,
      Long categoryId,
      String title,
      String summary,
      String markdownContent,
      Long sourceFileId,
      String fileName,
      WorkSpaceAclEnum visibility,
      DocParseStatusEnum parseStatus) {
    DocumentEntity entity =
        buildBase(
            workSpaceId,
            categoryId,
            title,
            summary,
            markdownContent,
            visibility,
            DocSourceTypeEnum.IMPORT,
            sourceFileId,
            fileName);
    entity.parseStatus = (parseStatus != null) ? parseStatus : DocParseStatusEnum.SUCCESS;
    return entity;
  }

  /** 静态工厂：从外部文件导入转化创建文档（支持显式指定解析状态） */
  public static DocumentEntity createFromImport(
      Long workSpaceId,
      Long categoryId,
      String title,
      String summary,
      String markdownContent,
      Long sourceFileId,
      WorkSpaceAclEnum visibility,
      DocParseStatusEnum parseStatus) {
    return createFromImport(
        workSpaceId,
        categoryId,
        title,
        summary,
        markdownContent,
        sourceFileId,
        null,
        visibility,
        parseStatus);
  }

  /** 静态工厂：从外部文件导入转化创建文档（默认解析成功） */
  public static DocumentEntity createFromImport(
      Long workSpaceId,
      Long categoryId,
      String title,
      String summary,
      String markdownContent,
      Long sourceFileId,
      WorkSpaceAclEnum visibility) {
    return createFromImport(
        workSpaceId,
        categoryId,
        title,
        summary,
        markdownContent,
        sourceFileId,
        null,
        visibility,
        DocParseStatusEnum.SUCCESS);
  }

  /** 静态工厂：由 AI 辅助生成创建文档 */
  public static DocumentEntity createFromAi(
      Long workSpaceId,
      Long categoryId,
      String title,
      String summary,
      String markdownContent,
      WorkSpaceAclEnum visibility) {
    return buildBase(
        workSpaceId,
        categoryId,
        title,
        summary,
        markdownContent,
        visibility,
        DocSourceTypeEnum.AI_GENERATED,
        null,
        null);
  }

  private static DocumentEntity buildBase(
      Long workSpaceId,
      Long categoryId,
      String title,
      String summary,
      String rawContent,
      WorkSpaceAclEnum visibility,
      DocSourceTypeEnum sourceType,
      Long sourceFileId,
      String fileName) {
    validateBaseAttributes(workSpaceId, title);

    DocumentEntity entity = new DocumentEntity();
    entity.workSpaceId = workSpaceId;
    entity.categoryId = categoryId == null ? 0L : categoryId;
    entity.title = title.trim();
    entity.summary = summary;
    entity.content = rawContent == null ? "" : rawContent;
    entity.status = DocumentStateEnum.PENDING_APPROVAL;
    entity.parseStatus = DocParseStatusEnum.SUCCESS;
    entity.visibility = visibility == null ? WorkSpaceAclEnum.INTERNAL : visibility;
    entity.sourceType = sourceType;
    entity.sourceFileId = sourceFileId;
    entity.fileName = fileName;
    entity.currentVersion = 1;
    entity.currentVersionTag = "v1.0";
    entity.publishedVersion = null;
    entity.wordCount = calculateWordCount(entity.content);
    entity.readCount = 0;
    return entity;
  }

  /** 标记异步解析成功并更新提取出的正文（文档标题严格使用文件名，禁止被正文解析内容中的标题覆盖） */
  public void markParseSuccess(String newContent, String suggestedTitle) {
    if (StrUtil.isBlank(this.title) && StrUtil.isNotBlank(suggestedTitle)) {
      this.title = suggestedTitle.trim();
    }
    this.content = (newContent == null) ? "" : newContent;
    this.wordCount = calculateWordCount(this.content);
    this.parseStatus = DocParseStatusEnum.SUCCESS;
  }

  /** 标记异步解析成功并更新提取出的正文 */
  public void markParseSuccess(String newContent) {
    markParseSuccess(newContent, null);
  }

  /** 标记异步解析失败 */
  public void markParseFailed(String errorMessage) {
    this.parseStatus = DocParseStatusEnum.FAILED;
  }

  /** 标记为排队等待中状态 */
  public void markQueued() {
    this.parseStatus = DocParseStatusEnum.QUEUED;
  }

  /** 重置为异步解析中状态 (供重试解析使用) */
  public void markAsyncProcessing() {
    this.parseStatus = DocParseStatusEnum.ASYNC_PROCESSING;
  }

  /** 获取解析状态（保底非空，历史数据默认为 SUCCESS） */
  public DocParseStatusEnum getParseStatus() {
    return this.parseStatus != null ? this.parseStatus : DocParseStatusEnum.SUCCESS;
  }

  /** 更新文档基本内容（PATCH 语义：入参为 null 表示不修改该字段，避免部分更新误清空数据） */
  public void updateContent(
      String newTitle, String newSummary, String newContent, Integer newWordCount) {
    assertCanEdit();
    if (StrUtil.isNotBlank(newTitle)) {
      this.title = newTitle.trim();
    }
    if (newSummary != null) {
      this.summary = newSummary;
    }
    if (newContent != null) {
      this.content = newContent;
      this.wordCount =
          (newWordCount != null && newWordCount >= 0)
              ? newWordCount
              : calculateWordCount(newContent);
    }
  }

  /** 变更所属分类目录 */
  public void updateCategory(Long newCategoryId) {
    this.categoryId = (newCategoryId == null || newCategoryId < 0) ? 0L : newCategoryId;
  }

  /** 调整访问权限级别 */
  public void updateVisibility(WorkSpaceAclEnum newVisibility) {
    if (newVisibility != null) {
      this.visibility = newVisibility;
    }
  }

  /** 设置文档封面（null 表示保持不变，空白串表示清除封面） */
  public void updateCover(String newCoverUrl) {
    if (newCoverUrl != null) {
      this.coverUrl = StrUtil.blankToDefault(newCoverUrl, null);
    }
  }

  /** 绑定原始文件资产 ID */
  public void bindSourceFile(Long sourceFileId) {
    this.sourceFileId = sourceFileId;
  }

  /** 变更文档生命周期状态（受状态机驱动） */
  public void transitionTo(DocumentStateEnum targetState) {
    Objects.requireNonNull(targetState, "目标状态不能为空");
    this.status = targetState;
  }

  /** 回归待审核状态（清除已审批发布标记） */
  public void revertToPendingApproval() {
    this.status = DocumentStateEnum.PENDING_APPROVAL;
    this.publishedVersion = null;
  }

  /** 升级导入新版本（状态回归待审核，旧版本审批状态失效） */
  public void upgradeToNewVersion(
      int nextVersion,
      String nextVersionTag,
      String newTitle,
      String newSummary,
      String newContent,
      Long newSourceFileId,
      String newFileName) {
    this.currentVersion = nextVersion;
    this.currentVersionTag =
        StrUtil.isNotBlank(nextVersionTag) ? nextVersionTag : "v" + nextVersion + ".0";
    if (StrUtil.isNotBlank(newTitle)) {
      this.title = newTitle.trim();
    }
    if (newSummary != null) {
      this.summary = newSummary;
    }
    this.content = newContent != null ? newContent : "";
    this.wordCount = calculateWordCount(this.content);
    if (newSourceFileId != null) {
      this.sourceFileId = newSourceFileId;
    }
    if (StrUtil.isNotBlank(newFileName)) {
      this.fileName = newFileName;
    }
    // 升级新版本后回归待审核状态
    this.status = DocumentStateEnum.PENDING_APPROVAL;
    // 旧版本审批状态失效回归未审批，同一文档最多只有一个处于审批通过状态
    this.publishedVersion = null;
  }

  /** 发布正式版本（设置审批通过版本号为当前版本） */
  public int publish(String customVersionTag) {
    if (StrUtil.isNotBlank(customVersionTag)) {
      this.currentVersionTag = customVersionTag;
    }
    this.status = DocumentStateEnum.APPROVED;
    this.publishedVersion = this.currentVersion;
    return this.currentVersion;
  }

  /** 发布正式版本快捷重载 */
  public int publish() {
    return publish(null);
  }

  /** 发布新正式版本（固化快照序号） */
  public int publishNewVersion(String customVersionTag) {
    if (this.status == DocumentStateEnum.APPROVED
        && Objects.equals(this.publishedVersion, this.currentVersion)) {
      this.currentVersion = this.currentVersion + 1;
      this.currentVersionTag =
          StrUtil.isNotBlank(customVersionTag)
              ? customVersionTag
              : "v" + this.currentVersion + ".0";
    } else if (StrUtil.isNotBlank(customVersionTag)) {
      this.currentVersionTag = customVersionTag;
    }
    this.status = DocumentStateEnum.APPROVED;
    this.publishedVersion = this.currentVersion;
    return this.currentVersion;
  }

  /** 回滚至指定历史版本正文 */
  public void rollbackToVersion(Integer versionNumber, String versionTitle, String versionContent) {
    if (StrUtil.isNotBlank(versionTitle)) {
      this.title = versionTitle;
    }
    if (versionContent != null) {
      this.content = versionContent;
      this.wordCount = calculateWordCount(versionContent);
    }
    if (versionNumber != null && versionNumber > 0) {
      this.currentVersion = versionNumber;
      this.currentVersionTag = "v" + versionNumber + ".0-rollback";
    }
    // 回滚后转为待审核状态待确认或重新审批，审批状态重置
    this.status = DocumentStateEnum.PENDING_APPROVAL;
    this.publishedVersion = null;
  }

  /** 获取导入来源文件名（若为空则回退为文档标题） */
  public String getFileName() {
    return StrUtil.isNotBlank(this.fileName) ? this.fileName : this.title;
  }

  /** 获取该文档对应的版本总数 */
  public Integer getVersionCount() {
    return (this.currentVersion != null && this.currentVersion > 0) ? this.currentVersion : 1;
  }

  /** 累加阅读量 */
  public void incrementReadCount() {
    this.readCount = this.readCount + 1;
  }

  /** 校验文档是否已正式发布/审批通过 */
  public boolean isPublished() {
    return this.status == DocumentStateEnum.APPROVED;
  }

  /** 校验文档当前是否可编辑（待审核状态处于审批锁定，不可编辑） */
  public void assertCanEdit() {
    if (this.status == DocumentStateEnum.PENDING_APPROVAL) {
      throw BusinessException.badRequest("文档当前正在审核中，不可编辑");
    }
  }

  /** 简易准确的 Markdown 字数统计（剔除常见标记符） */
  public static int calculateWordCount(String content) {
    if (StrUtil.isBlank(content)) {
      return 0;
    }
    // 过滤掉 markdown 标题标记、代码块反引号、加粗斜体符号与链接括号
    String stripped =
        content
            .replaceAll("#+\\s*", "")
            .replaceAll("```[a-zA-Z]*", "")
            .replaceAll("[`*_~\\[\\]()<>]", "")
            .replaceAll("\\s+", "");
    return stripped.length();
  }

  private static void validateBaseAttributes(Long workSpaceId, String title) {
    Objects.requireNonNull(workSpaceId, "所属工作区ID不能为空");
    if (StrUtil.isBlank(title)) {
      throw BusinessException.badRequest("文档标题不能为空");
    }
  }
}
