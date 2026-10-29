package io.lexiloom.application.document.search;

import co.elastic.clients.elasticsearch._types.FieldValue;
import io.lexiloom.application.document.api.dto.DocumentStateEnum;
import io.lexiloom.application.document.search.dto.DocSearchSortBy;
import io.lexiloom.application.document.search.dto.DocumentSearchRequest;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.query.HighlightQuery;
import org.springframework.data.elasticsearch.core.query.highlight.Highlight;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightField;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightParameters;

/**
 * 全文检索 NativeQuery 构建器
 *
 * <p>封装 Elasticsearch 8.x BoolQuery、多空间租户安全隔离、多字段打分与 Boost 加权逻辑。
 */
public final class DocSearchQueryBuilder {

  public static final int MAX_SNIPPET_LENGTH = 200;

  private DocSearchQueryBuilder() {}

  /** 构建检索 NativeQuery 规范对象 */
  public static NativeQuery build(
      DocumentSearchRequest request,
      boolean isMultiSource,
      List<DocSearchSourceMeta> activeSources,
      Map<Long, Float> boostMap) {

    NativeQueryBuilder builder = NativeQuery.builder();

    // 1. Bool Query: must + filter + should (boost)
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

                  // Filter 1: status = APPROVED (仅允许检索审批通过文档)
                  b.filter(
                      f -> f.term(t -> t.field("status").value(DocumentStateEnum.APPROVED.name())));

                  // Filter 2: categoryId (可选分类过滤)
                  if (request.categoryId() != null) {
                    b.filter(
                        f ->
                            f.term(
                                t ->
                                    t.field("categoryId")
                                        .value(String.valueOf(request.categoryId()))));
                  }

                  // Filter 3: tags (可选标签过滤)
                  if (request.tags() != null && !request.tags().isEmpty()) {
                    List<FieldValue> tagValues =
                        request.tags().stream().map(FieldValue::of).toList();
                    b.filter(f -> f.terms(t -> t.field("tags").terms(tq -> tq.value(tagValues))));
                  }

                  // Filter 4: 多空间租户安全隔离
                  if (!isMultiSource) {
                    // 单空间模式：直接 term 过滤主空间
                    b.filter(
                        f ->
                            f.term(
                                t ->
                                    t.field("workspaceId")
                                        .value(String.valueOf(request.workspaceId()))));
                  } else {
                    // 跨空间模式：复合 should + minimum_should_match=1 安全合流过滤
                    b.filter(
                        f ->
                            f.bool(
                                sb -> {
                                  sb.minimumShouldMatch("1");
                                  for (DocSearchSourceMeta source : activeSources) {
                                    switch (source.sourceType()) {
                                      case "PRIMARY" ->
                                          sb.should(
                                              sq ->
                                                  sq.term(
                                                      st ->
                                                          st.field("workspaceId")
                                                              .value(
                                                                  String.valueOf(
                                                                      source.workspaceId()))));
                                      case "CHILD" ->
                                          sb.should(
                                              sq ->
                                                  sq.bool(
                                                      cb ->
                                                          cb.filter(
                                                                  cf ->
                                                                      cf.term(
                                                                          ct ->
                                                                              ct.field(
                                                                                      "workspaceId")
                                                                                  .value(
                                                                                      String
                                                                                          .valueOf(
                                                                                              source
                                                                                                  .workspaceId()))))
                                                              .filter(
                                                                  cf ->
                                                                      cf.terms(
                                                                          ct ->
                                                                              ct.field("visibility")
                                                                                  .terms(
                                                                                      tq ->
                                                                                          tq.value(
                                                                                              List
                                                                                                  .of(
                                                                                                      FieldValue
                                                                                                          .of(
                                                                                                              "PUBLIC"),
                                                                                                      FieldValue
                                                                                                          .of(
                                                                                                              "INTERNAL"))))))));
                                      case "SPACE_LINK" -> {
                                        if (source.allowedDocIds() != null
                                            && !source.allowedDocIds().isEmpty()) {
                                          List<FieldValue> docIdValues =
                                              source.allowedDocIds().stream()
                                                  .map(
                                                      docId -> FieldValue.of(String.valueOf(docId)))
                                                  .toList();
                                          sb.should(
                                              sq ->
                                                  sq.bool(
                                                      lb ->
                                                          lb.filter(
                                                                  lf ->
                                                                      lf.term(
                                                                          lt ->
                                                                              lt.field(
                                                                                      "workspaceId")
                                                                                  .value(
                                                                                      String
                                                                                          .valueOf(
                                                                                              source
                                                                                                  .workspaceId()))))
                                                              .filter(
                                                                  lf ->
                                                                      lf.terms(
                                                                          lt ->
                                                                              lt.field("id")
                                                                                  .terms(
                                                                                      tq ->
                                                                                          tq.value(
                                                                                              docIdValues))))));
                                        }
                                      }
                                      default -> {}
                                    }
                                  }
                                  return sb;
                                }));

                    // 空间权重 Boost 打分倾斜：通过 should 给高权重空间提供相关性加权
                    if (boostMap != null && !boostMap.isEmpty()) {
                      for (DocSearchSourceMeta source : activeSources) {
                        float boost = boostMap.getOrDefault(source.workspaceId(), 1.0f);
                        b.should(
                            sq ->
                                sq.term(
                                    st ->
                                        st.field("workspaceId")
                                            .value(String.valueOf(source.workspaceId()))
                                            .boost(boost)));
                      }
                    }
                  }

                  return b;
                }));

    // 2. Highlight: 高亮配置
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
}
