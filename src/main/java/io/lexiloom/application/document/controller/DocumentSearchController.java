package io.lexiloom.application.document.controller;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.common.SecurityHolder;
import io.lexiloom.application.document.search.dto.DocumentSearchRequest;
import io.lexiloom.application.document.search.dto.DocumentSearchResponse;
import io.lexiloom.application.document.service.DocumentSearchQueryService;
import io.lexiloom.application.document.service.SearchHistoryService;
import io.lexiloom.application.document.service.SearchSuggestQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文档全文检索、搜索建议与历史记录 REST 控制器
 *
 * <p>严格遵循 Controller 纯粹性原则：仅负责参数校验与请求转发，不持有事务，不直接操作实体转换。
 */
@Validated
@RestController
@RequestMapping({"/search", "/api/search"})
@RequiredArgsConstructor
public class DocumentSearchController {

  private final DocumentSearchQueryService documentSearchQueryService;
  private final SearchSuggestQueryService searchSuggestQueryService;
  private final SearchHistoryService searchHistoryService;

  /**
   * 全文搜索接口 (GET 形式)：基于 Elasticsearch 提供多字段高亮全文检索与组合过滤
   *
   * @param request 搜索请求参数 (含关键词、工作区ID、分类、标签、排序、分页)
   * @return 高亮搜索分页结果
   */
  @GetMapping
  public DocumentSearchResponse search(@Valid @ModelAttribute DocumentSearchRequest request) {
    return documentSearchQueryService.search(request);
  }

  /**
   * 全文搜索接口 (POST 形式)：方便以 JSON Body 提交多空间复杂 weights 权重映射配置
   *
   * @param request 搜索请求参数 (含关键词、工作区ID、多空间权重分配等)
   * @return 高亮搜索分页结果
   */
  @PostMapping
  public DocumentSearchResponse searchPost(@Valid @RequestBody DocumentSearchRequest request) {
    return documentSearchQueryService.search(request);
  }

  /**
   * 搜索建议自动补全接口：基于前缀匹配与租户隔离提供候选词
   *
   * @param workspaceId 工作区ID
   * @param keyword 前缀关键词
   * @return 建议候选词列表 (最多 10 条)
   */
  @GetMapping("/suggest")
  public List<String> suggest(
      @NotNull(message = "工作空间ID不能为空") @RequestParam("workspaceId") Long workspaceId,
      @NotBlank(message = "关键词不能为空") @RequestParam("keyword") String keyword) {
    return searchSuggestQueryService.suggest(workspaceId, keyword);
  }

  /**
   * 查询当前用户最近搜索历史接口
   *
   * @param workspaceId 工作区ID (可选，多租户隔离)
   * @param size 获取条数 (默认 10 条)
   * @return 搜索历史关键词列表 (倒序排列)
   */
  @GetMapping("/history")
  public List<String> getHistory(
      @RequestParam(value = "workspaceId", required = false) Long workspaceId,
      @RequestParam(value = "size", defaultValue = "10") int size) {
    Long userId = SecurityHolder.getUserId();
    return searchHistoryService.getHistory(userId, workspaceId, size);
  }

  /**
   * 删除单条搜索历史记录（支持 QueryParam 传参，避免特殊字符在 PathVariable 中被拦截） 若未指定 keyword 则执行清空
   *
   * @param workspaceId 工作区ID (可选)
   * @param keyword 待删除关键词 (可选)
   */
  @DeleteMapping("/history")
  public ResponseEntity<Void> deleteHistory(
      @RequestParam(value = "workspaceId", required = false) Long workspaceId,
      @RequestParam(value = "keyword", required = false) String keyword) {
    Long userId = SecurityHolder.getUserId();
    if (StrUtil.isNotBlank(keyword)) {
      searchHistoryService.delete(userId, workspaceId, keyword);
    } else {
      searchHistoryService.clear(userId, workspaceId);
    }
    return ResponseEntity.noContent().build();
  }

  /**
   * 兼容原有按路径参数删除单条历史记录
   *
   * @param keyword 待删除关键词
   * @param workspaceId 工作区ID (可选)
   */
  @DeleteMapping("/history/{keyword}")
  public ResponseEntity<Void> deleteHistoryPath(
      @PathVariable("keyword") String keyword,
      @RequestParam(value = "workspaceId", required = false) Long workspaceId) {
    Long userId = SecurityHolder.getUserId();
    searchHistoryService.delete(userId, workspaceId, keyword);
    return ResponseEntity.noContent().build();
  }
}
