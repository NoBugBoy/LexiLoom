package io.lexiloom.application.document.search.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 全文检索请求参数 (全不可变 record)
 *
 * @param keyword 搜索关键词
 * @param workspaceId 发起检索的主工作空间 ID
 * @param categoryId 分类筛选 ID (可选)
 * @param tags 标签集合 (可选)
 * @param pageNum 页码 (从 1 开始，默认 1)
 * @param pageSize 每页条数 (默认 20，防拖库上限 100)
 * @param sortBy 排序枚举 (相关性 / 发布时间)
 * @param includeLinked 是否开启跨空间关联检索 (默认 false; 若未传但 weights 中包含关联空间则自动激活)
 * @param weights 空间权重分配字典 (key=workspaceId, value=权重数值，用于打分 Boost 偏好映射)
 */
public record DocumentSearchRequest(
    @NotBlank(message = "搜索关键词不能为空") String keyword,
    @NotNull(message = "工作区ID不能为空") Long workspaceId,
    Long categoryId,
    List<String> tags,
    Integer pageNum,
    Integer pageSize,
    DocSearchSortBy sortBy,
    Boolean includeLinked,
    Map<Long, Integer> weights) {

  public DocumentSearchRequest {
    pageNum = (pageNum == null || pageNum < 1) ? 1 : pageNum;
    pageSize = (pageSize == null || pageSize <= 0) ? 20 : Math.min(pageSize, 100);
    sortBy = sortBy == null ? DocSearchSortBy.RELEVANCE : sortBy;
    tags = tags == null ? Collections.emptyList() : List.copyOf(tags);
    keyword = keyword == null ? "" : keyword.trim();
    weights = weights != null ? Map.copyOf(weights) : Collections.emptyMap();
  }

  /** 兼容旧版 7 参数构造器 (默认仅检索当前工作区，不开启跨空间关联) */
  public DocumentSearchRequest(
      String keyword,
      Long workspaceId,
      Long categoryId,
      List<String> tags,
      Integer pageNum,
      Integer pageSize,
      DocSearchSortBy sortBy) {
    this(keyword, workspaceId, categoryId, tags, pageNum, pageSize, sortBy, null, null);
  }
}
