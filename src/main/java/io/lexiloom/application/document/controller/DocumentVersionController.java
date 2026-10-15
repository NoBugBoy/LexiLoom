package io.lexiloom.application.document.controller;

import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.document.api.dto.DocVersionCompareDTO;
import io.lexiloom.application.document.api.dto.DocVersionDTO;
import io.lexiloom.application.document.api.dto.DocumentDTO;
import io.lexiloom.application.document.model.request.DocVersionCreateRequest;
import io.lexiloom.application.document.version.DocVersionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 文档历史版本管理与多版本比对 REST 控制器 */
@RestController
@RequestMapping("/documents/{docId}/versions")
@RequiredArgsConstructor
public class DocumentVersionController {

  private final DocVersionService docVersionService;

  /** 手动创建里程碑版本快照 */
  @PostMapping
  public DocVersionDTO createSnapshot(
      @PathVariable("docId") Long docId,
      @Validated @RequestBody(required = false) DocVersionCreateRequest request) {
    Long userId = SecurityHolder.getUserId();
    return docVersionService.createSnapshot(docId, request, userId);
  }

  /** 获取文档历史版本列表 */
  @GetMapping
  public List<DocVersionDTO> listVersions(@PathVariable("docId") Long docId) {
    Long userId = SecurityHolder.getUserId();
    return docVersionService.listVersions(docId, userId);
  }

  /** 获取单次历史版本详情 */
  @GetMapping("/{versionNumber}")
  public DocVersionDTO getVersion(
      @PathVariable("docId") Long docId, @PathVariable("versionNumber") Integer versionNumber) {
    Long userId = SecurityHolder.getUserId();
    return docVersionService.getVersion(docId, versionNumber, userId);
  }

  /** 一键回滚到历史版本 (由 Service 完成实体 DTO 转换，Controller 不感知领域聚合根) */
  @PostMapping("/{versionNumber}/rollback")
  public DocumentDTO rollback(
      @PathVariable("docId") Long docId, @PathVariable("versionNumber") Integer versionNumber) {
    Long userId = SecurityHolder.getUserId();
    return docVersionService.rollbackToVersion(docId, versionNumber, userId);
  }

  /**
   * 提取两版本原文对比数据（供前端 Diff 渲染器渲染）
   *
   * @param oldVersion 旧版本号（留空或 0 为基线）
   * @param newVersion 新版本号（留空则对比当前未发布草稿）
   */
  @GetMapping("/compare")
  public DocVersionCompareDTO compare(
      @PathVariable("docId") Long docId,
      @RequestParam(value = "oldVersion", required = false) Integer oldVersion,
      @RequestParam(value = "newVersion", required = false) Integer newVersion) {
    Long userId = SecurityHolder.getUserId();
    return docVersionService.compareVersions(docId, oldVersion, newVersion, userId);
  }
}
