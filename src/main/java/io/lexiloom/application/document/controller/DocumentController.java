package io.lexiloom.application.document.controller;

import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.document.api.dto.DocParseStatusDTO;
import io.lexiloom.application.document.api.dto.DocumentDTO;
import io.lexiloom.application.document.model.request.DocumentPageRequest;
import io.lexiloom.application.document.model.request.DocumentTransitionRequest;
import io.lexiloom.application.document.model.request.DocumentUpdateRequest;
import io.lexiloom.application.document.parser.DocParserEngineEnum;
import io.lexiloom.application.document.pipeline.DocumentImportResult;
import io.lexiloom.application.document.service.DocumentCommandService;
import io.lexiloom.application.document.service.DocumentQueryService;
import io.lexiloom.application.enums.WorkSpaceAclEnum;
import io.lexiloom.application.exception.BusinessException;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 文档核心 REST 控制器 */
@RestController
@RequestMapping("/documents")
@Validated
@RequiredArgsConstructor
public class DocumentController {

  private final DocumentCommandService documentCommandService;
  private final DocumentQueryService documentQueryService;

  /** 查阅文档详情 */
  @GetMapping("/{id}")
  public DocumentDTO getById(@PathVariable("id") Long id) {
    Long userId = SecurityHolder.getUserId();
    return documentQueryService
        .getById(id, userId)
        .orElseThrow(() -> BusinessException.notFound("文档不存在: " + id));
  }

  /** 文档解析状态轻量轮询接口 (供前端异步解析时低开销轮询) */
  @GetMapping("/{id}/parse-status")
  public DocParseStatusDTO getParseStatus(@PathVariable("id") Long id) {
    Long userId = SecurityHolder.getUserId();
    return documentQueryService.getParseStatus(id, userId);
  }

  /** 修改编辑文档 */
  @PutMapping("/{id}")
  public DocumentDTO update(
      @PathVariable("id") Long id, @Validated @RequestBody DocumentUpdateRequest request) {
    Long userId = SecurityHolder.getUserId();
    return documentCommandService.update(id, request, userId);
  }

  /** 删除文档 */
  @DeleteMapping("/{id}")
  public void delete(@PathVariable("id") Long id) {
    Long userId = SecurityHolder.getUserId();
    documentCommandService.delete(id, userId);
  }

  /** 动态多条件分页检索文档 */
  @PostMapping("/page")
  public Page<DocumentDTO> page(@Validated @RequestBody DocumentPageRequest request) {
    Long userId = SecurityHolder.getUserId();
    return documentQueryService.page(request, userId);
  }

  /** 文档生命周期状态流转 (提交、审批、发布、驳回、归档) */
  @PostMapping("/{id}/transition")
  public DocumentDTO transitionState(
      @PathVariable("id") Long id, @Validated @RequestBody DocumentTransitionRequest request) {
    Long userId = SecurityHolder.getUserId();
    return documentCommandService.transitionState(id, request, userId);
  }

  /** 多源文件上传导入管线（支持新文档导入或指定已有文档升级新版本） */
  @PostMapping("/import")
  public DocumentImportResult importFile(
      @RequestParam("workSpaceId") @NotNull(message = "工作空间ID不能为空") Long workSpaceId,
      @RequestParam(value = "documentId", required = false) Long documentId,
      @RequestParam(value = "categoryId", required = false) Long categoryId,
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "preferredEngine", required = false)
          DocParserEngineEnum preferredEngine,
      @RequestParam(value = "visibility", required = false) WorkSpaceAclEnum visibility) {
    Long userId = SecurityHolder.getUserId();
    return documentCommandService.importFile(
        workSpaceId, documentId, categoryId, file, preferredEngine, visibility, userId);
  }

  /** 针对解析失败或超时的导入文档重新发起解析 */
  @PostMapping("/{id}/retry-parse")
  public void retryParse(@PathVariable("id") Long id) {
    Long userId = SecurityHolder.getUserId();
    documentCommandService.retryParse(id, userId);
  }
}
