package com.knowflow.application.document.service.impl;

import cn.hutool.core.util.StrUtil;
import co.elastic.clients.elasticsearch._types.FieldValue;
import com.knowflow.application.common.ErrorCode;
import com.knowflow.application.common.LoginUserAuthentication;
import com.knowflow.application.common.SecurityHolder;
import com.knowflow.application.document.search.DocSearchDocument;
import com.knowflow.application.document.search.dto.DocSearchItemVO;
import com.knowflow.application.document.search.dto.DocSearchSortBy;
import com.knowflow.application.document.search.dto.DocumentSearchRequest;
import com.knowflow.application.document.search.dto.DocumentSearchResponse;
import com.knowflow.application.document.service.DocumentSearchQueryService;
import com.knowflow.application.document.service.SearchHistoryService;
import com.knowflow.application.document.statemachine.DocumentStateEnum;
import com.knowflow.application.exception.BusinessException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.HighlightQuery;
import org.springframework.data.elasticsearch.core.query.highlight.Highlight;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightField;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightParameters;
import org.springframework.stereotype.Service;

/**
 * 文档全文检索查询服务实现
 *
 * <p>基于 Elasticsearch 8.x + IK 分词构建 NativeQuery，包含多字段打分、工作区租户隔离、高亮及分页， 并在检索完成后非阻塞异步记录用户历史搜索。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentSearchQueryServiceImpl implements DocumentSearchQueryService {

  private static final int MAX_SNIPPET_LENGTH = 200;

  private final ElasticsearchOperations elasticsearchOperations;
  private final SearchHistoryService searchHistoryService;

  @Override
  public DocumentSearchResponse search(DocumentSearchRequest request) {
    if (request == null || StrUtil.isBlank(request.keyword()) || request.workspaceId() == null) {
      throw new BusinessException(ErrorCode.Common.BAD_REQUEST, "检索关键词与工作区ID不能为空");
    }

    try {
      NativeQuery query = buildNativeQuery(request);
      SearchHits<DocSearchDocument> searchHits =
          elasticsearchOperations.search(query, DocSearchDocument.class);

      long total = searchHits.getTotalHits();
      List<DocSearchItemVO> items =
          searchHits.getSearchHits().stream().map(this::mapToItemVO).toList();

      // 异步记录搜索历史 (非阻塞)
      recordSearchHistoryAsync(request);

      return DocumentSearchResponse.of(total, request.pageSize(), items);
    } catch (BusinessException be) {
      throw be;
    } catch (Exception ex) {
      log.error(
          "Elasticsearch 检索执行失败: keyword={}, workspaceId={}, error={}",
          request.keyword(),
          request.workspaceId(),
          ex.getMessage(),
          ex);
      throw new BusinessException(ErrorCode.Document.ES_SEARCH_FAILED, "全文检索服务异常，请稍后重试");
    }
  }

  private NativeQuery buildNativeQuery(DocumentSearchRequest request) {
    NativeQueryBuilder builder = NativeQuery.builder();

    // 1. Bool Query: must + filter
    builder.withQuery(
        q ->
            q.bool(
                b -> {
                  // Must: multi_match 跨多字段全文检索，设置不同字段检索打分权重
                  b.must(
                      m ->
                          m.multiMatch(
                              mm ->
                                  mm.query(request.keyword())
                                      .fields(
                                          List.of(
                                              "title^3", "summary^2", "content^1", "rawText^1"))));

                  // Filter 1: workspaceId (多租户安全隔离，强制过滤)
                  b.filter(
                      f ->
                          f.term(
                              t ->
                                  t.field("workspaceId")
                                      .value(String.valueOf(request.workspaceId()))));

                  // Filter 2: status = APPROVED (仅允许检索审批通过文档)
                  b.filter(
                      f -> f.term(t -> t.field("status").value(DocumentStateEnum.APPROVED.name())));

                  // Filter 3: categoryId (可选分类过滤)
                  if (request.categoryId() != null) {
                    b.filter(
                        f ->
                            f.term(
                                t ->
                                    t.field("categoryId")
                                        .value(String.valueOf(request.categoryId()))));
                  }

                  // Filter 4: tags (可选标签过滤)
                  if (request.tags() != null && !request.tags().isEmpty()) {
                    List<FieldValue> tagValues =
                        request.tags().stream().map(FieldValue::of).toList();
                    b.filter(f -> f.terms(t -> t.field("tags").terms(tq -> tq.value(tagValues))));
                  }

                  return b;
                }));

    // 2. Highlight: 高亮配置 (配置 fragmentSize，并在 Java 端提供 HTML 标签自闭合安全截断)
    HighlightParameters highlightParameters =
        HighlightParameters.builder()
            .withPreTags("<em class=\"hl\">")
            .withPostTags("</em>")
            .withNumberOfFragments(1)
            .withFragmentSize(MAX_SNIPPET_LENGTH)
            .build();

    List<HighlightField> highlightFields =
        List.of(
            new HighlightField("title"),
            new HighlightField("summary"),
            new HighlightField("content"),
            new HighlightField("rawText"));

    Highlight highlight = new Highlight(highlightParameters, highlightFields);
    builder.withHighlightQuery(new HighlightQuery(highlight, DocSearchDocument.class));

    // 3. Sort: 相关性降序 或 发布时间倒序
    Sort sort =
        request.sortBy() == DocSearchSortBy.PUBLISHED_AT
            ? Sort.by(Sort.Direction.DESC, "publishedAt")
            : Sort.by(Sort.Direction.DESC, "_score");

    // 4. Pagination & 精确总命中数
    Pageable pageable = PageRequest.of(request.pageNum() - 1, request.pageSize(), sort);
    builder.withPageable(pageable);
    builder.withTrackTotalHits(true);

    return builder.build();
  }

  private DocSearchItemVO mapToItemVO(SearchHit<DocSearchDocument> hit) {
    DocSearchDocument doc = hit.getContent();
    Map<String, List<String>> hlMap = hit.getHighlightFields();

    // title 高亮
    String title = extractFirstHighlight(hlMap, "title", doc.getTitle());

    // summary 高亮
    String summary = extractFirstHighlight(hlMap, "summary", doc.getSummary());

    // hitSnippet: 优先从 rawText > content 高亮摘要中提取，缺失时回退
    String hitSnippet = extractHitSnippet(hlMap, doc);

    return new DocSearchItemVO(
        doc.getId(),
        title,
        summary,
        hitSnippet,
        doc.getCategoryId(),
        doc.getTags(),
        doc.getSourceType(),
        doc.getPublishedAt());
  }

  private String extractHitSnippet(Map<String, List<String>> hlMap, DocSearchDocument doc) {
    // 优先 1: rawText 高亮
    List<String> rawTextHl = hlMap.get("rawText");
    if (rawTextHl != null && !rawTextHl.isEmpty()) {
      return safeTruncateHtml(String.join(" ... ", rawTextHl), MAX_SNIPPET_LENGTH);
    }

    // 优先 2: content 高亮
    List<String> contentHl = hlMap.get("content");
    if (contentHl != null && !contentHl.isEmpty()) {
      return safeTruncateHtml(String.join(" ... ", contentHl), MAX_SNIPPET_LENGTH);
    }

    // 优先 3: 回退到 summary
    if (StrUtil.isNotBlank(doc.getSummary())) {
      return safeTruncateHtml(doc.getSummary(), MAX_SNIPPET_LENGTH);
    }

    // 优先 4: 回退到 rawText 原文
    if (StrUtil.isNotBlank(doc.getRawText())) {
      return safeTruncateHtml(doc.getRawText(), MAX_SNIPPET_LENGTH);
    }

    // 优先 5: 回退到 Markdown content
    if (StrUtil.isNotBlank(doc.getContent())) {
      return safeTruncateHtml(doc.getContent(), MAX_SNIPPET_LENGTH);
    }

    return "";
  }

  private String extractFirstHighlight(
      Map<String, List<String>> hlMap, String field, String defaultValue) {
    List<String> fragments = hlMap.get(field);
    if (fragments != null && !fragments.isEmpty()) {
      return String.join("...", fragments);
    }
    return defaultValue != null ? defaultValue : "";
  }

  /** 安全截断文本，确保 HTML 高亮标签（如 {@code <em class="hl">} 与 {@code </em>}）完整闭合， 避免破坏前端 DOM 结构与页面文本样式。 */
  private String safeTruncateHtml(String text, int maxLength) {
    if (text == null) {
      return "";
    }
    if (text.length() <= maxLength) {
      return text;
    }

    String truncated = text.substring(0, maxLength);

    // 若恰好截断在 HTML 标签内部 (如 <em cl...)，回退到标签开始符号之前
    int lastOpenBracket = truncated.lastIndexOf('<');
    int lastCloseBracket = truncated.lastIndexOf('>');
    if (lastOpenBracket > lastCloseBracket) {
      truncated = truncated.substring(0, lastOpenBracket);
    }

    // 检查 <em class="hl"> 与 </em> 的出现次数，若标签未闭合则自动追加 </em>
    int openCount = StrUtil.count(truncated, "<em class=\"hl\">");
    int closeCount = StrUtil.count(truncated, "</em>");

    StringBuilder sb = new StringBuilder(truncated).append("...");
    for (int i = 0; i < openCount - closeCount; i++) {
      sb.append("</em>");
    }

    return sb.toString();
  }

  private void recordSearchHistoryAsync(DocumentSearchRequest request) {
    try {
      Long userId = SecurityHolder.getLoginUser().map(LoginUserAuthentication::userId).orElse(null);
      if (userId != null && StrUtil.isNotBlank(request.keyword())) {
        searchHistoryService.record(userId, request.workspaceId(), request.keyword());
      }
    } catch (Exception ex) {
      log.warn("异步记录搜索历史失败 (非阻塞): {}", ex.getMessage());
    }
  }
}
