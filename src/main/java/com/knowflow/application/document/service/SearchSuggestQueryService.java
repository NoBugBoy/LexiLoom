package com.knowflow.application.document.service;

import java.util.List;

/** 搜索建议与自动补全服务 */
public interface SearchSuggestQueryService {

  /**
   * 基于标题与前缀提供候选词自动补全提示
   *
   * @param workspaceId 空间 ID
   * @param keyword 前缀关键词
   * @return 最多 10 条建议提示词列表
   */
  List<String> suggest(Long workspaceId, String keyword);
}
