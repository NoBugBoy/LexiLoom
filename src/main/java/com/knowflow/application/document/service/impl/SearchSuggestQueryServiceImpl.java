package com.knowflow.application.document.service.impl;

import cn.hutool.core.util.StrUtil;
import com.knowflow.application.document.search.DocSearchDocument;
import com.knowflow.application.document.service.SearchSuggestQueryService;
import com.knowflow.application.document.statemachine.DocumentStateEnum;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

/**
 * 搜索建议与自动补全服务实现
 *
 * <p>基于 {@code matchPhrasePrefix} 查询并配合 workspaceId / status 过滤， 提供多租户安全的实时标题补全，最多返回 10 条候选词。
 *
 * <p><b>安全说明</b>：Elasticsearch Completion Suggester 在分片级独立执行，<b>不受</b> bool.filter
 * 约束，直接使用会导致跨租户数据泄露。因此此处改用带隔离过滤的 matchPhrasePrefix 方案。若未来需要真正的 Completion Suggester，需配合
 * {@code @CompletionContext(workspaceId)} 使用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchSuggestQueryServiceImpl implements SearchSuggestQueryService {

  private static final int MAX_SUGGEST_SIZE = 10;

  private final ElasticsearchOperations elasticsearchOperations;

  @Override
  public List<String> suggest(Long workspaceId, String keyword) {
    if (workspaceId == null || StrUtil.isBlank(keyword)) {
      return Collections.emptyList();
    }
    String cleanKeyword = keyword.trim();

    try {
      NativeQueryBuilder builder = NativeQuery.builder();

      // matchPhrasePrefix + workspaceId/status filter 组合，保证多租户隔离安全
      builder.withQuery(
          q ->
              q.bool(
                  b -> {
                    b.must(m -> m.matchPhrasePrefix(mpp -> mpp.field("title").query(cleanKeyword)));
                    b.filter(
                        f ->
                            f.term(t -> t.field("workspaceId").value(String.valueOf(workspaceId))));
                    b.filter(
                        f ->
                            f.term(
                                t -> t.field("status").value(DocumentStateEnum.APPROVED.name())));
                    return b;
                  }));

      builder.withPageable(PageRequest.of(0, MAX_SUGGEST_SIZE));

      SearchHits<DocSearchDocument> searchHits =
          elasticsearchOperations.search(builder.build(), DocSearchDocument.class);

      Set<String> candidateSet = new LinkedHashSet<>();
      for (SearchHit<DocSearchDocument> hit : searchHits.getSearchHits()) {
        DocSearchDocument doc = hit.getContent();
        if (doc != null && StrUtil.isNotBlank(doc.getTitle())) {
          candidateSet.add(doc.getTitle().trim());
        }
        if (candidateSet.size() >= MAX_SUGGEST_SIZE) {
          break;
        }
      }

      return candidateSet.stream().limit(MAX_SUGGEST_SIZE).toList();
    } catch (Exception ex) {
      log.error(
          "Elasticsearch 搜索建议执行失败: workspaceId={}, keyword={}, error={}",
          workspaceId,
          cleanKeyword,
          ex.getMessage(),
          ex);
      return Collections.emptyList();
    }
  }
}
