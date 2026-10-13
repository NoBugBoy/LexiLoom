package com.knowflow.application.document.version;

import cn.hutool.core.util.StrUtil;
import com.knowflow.application.common.ErrorCode;
import com.knowflow.application.document.acl.DocAccessControlService;
import com.knowflow.application.document.api.dto.DocVersionCompareDTO;
import com.knowflow.application.document.api.dto.DocVersionDTO;
import com.knowflow.application.document.mapper.DocVersionMapper;
import com.knowflow.application.document.model.entity.DocVersionEntity;
import com.knowflow.application.document.model.entity.DocumentEntity;
import com.knowflow.application.document.repository.DocVersionRepository;
import com.knowflow.application.document.repository.DocumentRepository;
import com.knowflow.application.document.statemachine.DocumentStateEnum;
import com.knowflow.application.exception.BusinessException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 文档版本快照管理与多版本比对用例服务 负责不可变里程碑快照生成、版本内容提取比对、版本列表拉取与一键回退 （注：Diff 逐行高亮计算移至前端渲染，后端提供纯净的原文本对比契约） */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocVersionService {

  private final DocumentRepository documentRepository;
  private final DocVersionRepository docVersionRepository;
  private final DocVersionMapper docVersionMapper;
  private final com.knowflow.application.document.mapper.DocumentMapper documentMapper;
  private final DocAccessControlService docAccessControlService;
  private final ApplicationEventPublisher eventPublisher;

  /** 创建不可变里程碑版本快照 (支持 Request 对象直接传入) */
  @Transactional
  public DocVersionDTO createSnapshot(
      Long documentId,
      com.knowflow.application.document.model.request.DocVersionCreateRequest request,
      Long publisherId) {
    String tag = request != null ? request.versionTag() : null;
    String summary = request != null ? request.changeSummary() : null;
    return createSnapshot(documentId, tag, summary, publisherId);
  }

  /** 创建不可变里程碑版本快照 */
  @Transactional
  public DocVersionDTO createSnapshot(
      Long documentId, String customVersionTag, String changeSummary, Long publisherId) {
    DocumentEntity doc =
        documentRepository
            .findById(documentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.Document.DOC_NOT_FOUND, "文档不存在"));

    docAccessControlService.assertCanWrite(doc, publisherId);

    int targetVersionNumber = doc.getCurrentVersion();
    // 检查当前版本快照是否已存在，若已存在则递增版本并创建新快照
    if (docVersionRepository
        .findByDocumentIdAndVersionNumber(documentId, targetVersionNumber)
        .isPresent()) {
      targetVersionNumber = doc.getCurrentVersion() + 1;
      String nextTag =
          StrUtil.isNotBlank(customVersionTag)
              ? customVersionTag
              : "v" + targetVersionNumber + ".0";
      doc.upgradeToNewVersion(
          targetVersionNumber,
          nextTag,
          doc.getTitle(),
          doc.getSummary(),
          doc.getContent(),
          doc.getSourceFileId(),
          doc.getFileName());
    } else {
      doc.publish(customVersionTag);
    }

    DocVersionEntity snapshot =
        DocVersionEntity.createSnapshot(
            doc.getWorkSpaceId(),
            doc.getId(),
            targetVersionNumber,
            doc.getCurrentVersionTag(),
            doc.getTitle(),
            doc.getContent(),
            changeSummary,
            publisherId);

    DocVersionEntity savedSnapshot = docVersionRepository.save(snapshot);
    documentRepository.save(doc);

    log.info(
        "【DocVersionService】文档生成新版本快照成功: docId={}, version={}, tag={}, publisherId={}",
        doc.getId(),
        savedSnapshot.getVersionNumber(),
        savedSnapshot.getVersionTag(),
        publisherId);

    DocumentStateEnum versionStatus =
        (doc.getStatus() == DocumentStateEnum.APPROVED
                && java.util.Objects.equals(
                    doc.getPublishedVersion(), savedSnapshot.getVersionNumber()))
            ? DocumentStateEnum.APPROVED
            : DocumentStateEnum.PENDING_APPROVAL;

    return docVersionMapper.toDTO(savedSnapshot, versionStatus);
  }

  /** 一键回退到指定历史版本 */
  @Transactional
  public com.knowflow.application.document.api.dto.DocumentDTO rollbackToVersion(
      Long documentId, Integer targetVersionNumber, Long operatorId) {
    DocumentEntity doc =
        documentRepository
            .findById(documentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.Document.DOC_NOT_FOUND, "文档不存在"));

    docAccessControlService.assertCanWrite(doc, operatorId);

    DocVersionEntity targetVersion =
        docVersionRepository
            .findByDocumentIdAndVersionNumber(documentId, targetVersionNumber)
            .orElseThrow(
                () ->
                    new BusinessException(
                        ErrorCode.Document.DOC_VERSION_NOT_FOUND,
                        "指定版本快照不存在: v" + targetVersionNumber));

    DocumentStateEnum originalStatus = doc.getStatus();

    doc.rollbackToVersion(
        targetVersion.getVersionNumber(), targetVersion.getTitle(), targetVersion.getContent());

    DocumentEntity updatedDoc = documentRepository.save(doc);

    // 若原文档已处于审批通过状态，回滚后转为待审核，发布流转事件以从 Elasticsearch 索引中安全撤出
    if (originalStatus == DocumentStateEnum.APPROVED) {
      eventPublisher.publishEvent(
          new com.knowflow.application.document.statemachine.DocumentStateTransitionEvent(
              doc.getId(),
              originalStatus,
              DocumentStateEnum.PENDING_APPROVAL,
              com.knowflow.application.document.statemachine.DocumentEventEnum.SUBMIT,
              operatorId,
              "回滚至历史版本",
              java.time.LocalDateTime.now()));
    }

    log.info(
        "【DocVersionService】文档已成功回退至版本: docId={}, targetVersion={}, operatorId={}",
        documentId,
        targetVersionNumber,
        operatorId);

    return documentMapper.toDTO(updatedDoc);
  }

  /**
   * 提取两个版本间的原始内容供前端 Diff 组件比对展示（支持最新版本与选中版本对比）
   *
   * @param documentId 文档 ID
   * @param oldVersionNumber 旧版本号（若为 0 或 null 则视为空白基线）
   * @param newVersionNumber 新版本号（若为 null 则对比当前最新版本内容）
   */
  @Transactional(readOnly = true)
  public DocVersionCompareDTO compareVersions(
      Long documentId, Integer oldVersionNumber, Integer newVersionNumber, Long userId) {
    DocumentEntity doc =
        documentRepository
            .findById(documentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.Document.DOC_NOT_FOUND, "文档不存在"));

    if (userId != null) {
      docAccessControlService.assertCanRead(doc, userId);
    }

    String oldText = "";
    String oldTag = "baseline";

    if (oldVersionNumber != null && oldVersionNumber > 0) {
      DocVersionEntity oldVer =
          docVersionRepository
              .findByDocumentIdAndVersionNumber(documentId, oldVersionNumber)
              .orElseThrow(
                  () ->
                      new BusinessException(
                          ErrorCode.Document.DOC_VERSION_NOT_FOUND,
                          "旧版本不存在: v" + oldVersionNumber));
      oldText = oldVer.getContent();
      oldTag = oldVer.getVersionTag();
    }

    String newText;
    String newTag;
    Integer resolvedNewVersionNumber;

    if (newVersionNumber == null) {
      resolvedNewVersionNumber = doc.getCurrentVersion();
      newText = doc.getContent();
      newTag =
          cn.hutool.core.util.StrUtil.isNotBlank(doc.getCurrentVersionTag())
              ? doc.getCurrentVersionTag()
              : "latest";
    } else {
      resolvedNewVersionNumber = newVersionNumber;
      DocVersionEntity newVer =
          docVersionRepository
              .findByDocumentIdAndVersionNumber(documentId, newVersionNumber)
              .orElseThrow(
                  () ->
                      new BusinessException(
                          ErrorCode.Document.DOC_VERSION_NOT_FOUND,
                          "新版本不存在: v" + newVersionNumber));
      newText = newVer.getContent();
      newTag = newVer.getVersionTag();
    }

    log.info(
        "【DocVersionService】输出两版本比对文本数据: docId={}, oldVer={}, newVer={}",
        documentId,
        oldVersionNumber,
        resolvedNewVersionNumber);

    return new DocVersionCompareDTO(
        documentId, oldVersionNumber, oldTag, oldText, resolvedNewVersionNumber, newTag, newText);
  }

  @Transactional(readOnly = true)
  public DocVersionCompareDTO compareVersions(
      Long documentId, Integer oldVersionNumber, Integer newVersionNumber) {
    return compareVersions(documentId, oldVersionNumber, newVersionNumber, null);
  }

  /** 查询文档所有历史版本列表（按版本序号倒序，返回版本审批状态） */
  @Transactional(readOnly = true)
  public List<DocVersionDTO> listVersions(Long documentId, Long userId) {
    DocumentEntity doc =
        documentRepository
            .findById(documentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.Document.DOC_NOT_FOUND, "文档不存在"));
    if (userId != null) {
      docAccessControlService.assertCanRead(doc, userId);
    }
    List<DocVersionEntity> list =
        docVersionRepository.findByDocumentIdOrderByVersionNumberDesc(documentId);
    Integer publishedVersion =
        doc.getStatus() == DocumentStateEnum.APPROVED ? doc.getPublishedVersion() : null;

    return list.stream()
        .map(
            v -> {
              DocumentStateEnum versionStatus =
                  (publishedVersion != null && publishedVersion.equals(v.getVersionNumber()))
                      ? DocumentStateEnum.APPROVED
                      : DocumentStateEnum.PENDING_APPROVAL;
              return docVersionMapper.toDTO(v, versionStatus);
            })
        .toList();
  }

  @Transactional(readOnly = true)
  public List<DocVersionDTO> listVersions(Long documentId) {
    return listVersions(documentId, null);
  }

  /** 获取单次历史版本详情 */
  @Transactional(readOnly = true)
  public DocVersionDTO getVersion(Long documentId, Integer versionNumber, Long userId) {
    DocumentEntity doc =
        documentRepository
            .findById(documentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.Document.DOC_NOT_FOUND, "文档不存在"));
    if (userId != null) {
      docAccessControlService.assertCanRead(doc, userId);
    }
    DocVersionEntity entity =
        docVersionRepository
            .findByDocumentIdAndVersionNumber(documentId, versionNumber)
            .orElseThrow(
                () ->
                    new BusinessException(
                        ErrorCode.Document.DOC_VERSION_NOT_FOUND, "版本快照不存在: v" + versionNumber));

    Integer publishedVersion =
        doc.getStatus() == DocumentStateEnum.APPROVED ? doc.getPublishedVersion() : null;
    DocumentStateEnum versionStatus =
        (publishedVersion != null && publishedVersion.equals(entity.getVersionNumber()))
            ? DocumentStateEnum.APPROVED
            : DocumentStateEnum.PENDING_APPROVAL;

    return docVersionMapper.toDTO(entity, versionStatus);
  }

  @Transactional(readOnly = true)
  public DocVersionDTO getVersion(Long documentId, Integer versionNumber) {
    return getVersion(documentId, versionNumber, null);
  }
}
