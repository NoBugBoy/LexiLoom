package io.lexiloom.application.document.service;

import io.lexiloom.application.document.search.dto.DocumentSearchRequest;
import io.lexiloom.application.document.search.dto.DocumentSearchResponse;

/** 文档全文检索查询服务 */
public interface DocumentSearchQueryService {

  /**
   * 执行多字段高亮全文搜索
   *
   * @param request 搜索请求
   * @return 分页搜索结果
   */
  DocumentSearchResponse search(DocumentSearchRequest request);
}
