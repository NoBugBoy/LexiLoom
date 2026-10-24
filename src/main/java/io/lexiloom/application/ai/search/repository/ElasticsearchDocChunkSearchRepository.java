package io.lexiloom.application.ai.search.repository;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import io.lexiloom.application.ai.model.search.DocChunkDocument;
import io.lexiloom.application.ai.search.HybridSearchCommand;
import io.lexiloom.application.ai.search.domain.LexicalQuery;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Repository;

/** Elasticsearch 8.x 文档切块检索仓储实现 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ElasticsearchDocChunkSearchRepository implements DocChunkSearchRepository {

  private final ElasticsearchOperations elasticsearchOperations;

  @Override
  public List<DocChunkDocument> searchLexical(
      HybridSearchCommand command,
      LexicalQuery lexicalQuery,
      Long workspaceId,
      boolean onlyPublic,
      List<Long> allowedDocIds,
      int limit) {
    try {
      String queryText = lexicalQuery != null ? lexicalQuery.cleanedQuery() : command.query();
      NativeQueryBuilder builder = NativeQuery.builder();
      builder.withQuery(
          q ->
              q.bool(
                  b -> {
                    b.must(
                        m ->
                            m.multiMatch(
                                mm ->
                                    mm.query(queryText)
                                        .type(TextQueryType.MostFields)
                                        .tieBreaker(0.3)
                                        .fields(
                                            List.of(
                                                "content^4",
                                                "title^3",
                                                "headerPath^2",
                                                "contextPrefix^0.5"))));

                    applyCommonFilters(b, command, workspaceId, onlyPublic, allowedDocIds);
                    return b;
                  }));

      builder.withPageable(PageRequest.of(0, limit));
      SearchHits<DocChunkDocument> hits =
          elasticsearchOperations.search(builder.build(), DocChunkDocument.class);

      return extractDocuments(hits);
    } catch (Exception e) {
      log.warn(
          "【HybridSearch】BM25 词法全文检索异常: workspaceId={}, query=[{}], error={}",
          workspaceId,
          command.query(),
          e.getMessage());
      return List.of();
    }
  }

  @Override
  public List<DocChunkDocument> searchVector(
      HybridSearchCommand command,
      float[] queryVector,
      Long workspaceId,
      boolean onlyPublic,
      List<Long> allowedDocIds,
      int limit) {
    if (queryVector == null || queryVector.length == 0) {
      return List.of();
    }
    try {
      List<Float> vectorList = new ArrayList<>(queryVector.length);
      for (float f : queryVector) {
        vectorList.add(f);
      }

      NativeQueryBuilder builder = NativeQuery.builder();
      builder.withKnnSearches(
          knn -> {
            knn.field("content_vector")
                .queryVector(vectorList)
                .k(limit)
                .numCandidates(Math.max(limit * 2, 50))
                .filter(
                    f ->
                        f.bool(
                            b -> {
                              applyCommonFilters(
                                  b, command, workspaceId, onlyPublic, allowedDocIds);
                              return b;
                            }));
            return knn;
          });

      builder.withPageable(PageRequest.of(0, limit));
      SearchHits<DocChunkDocument> hits =
          elasticsearchOperations.search(builder.build(), DocChunkDocument.class);

      return extractDocuments(hits);
    } catch (Exception e) {
      log.warn(
          "【HybridSearch】kNN 向量检索异常: workspaceId={}, query=[{}], error={}",
          workspaceId,
          command.query(),
          e.getMessage());
      return List.of();
    }
  }

  @Override
  public List<DocChunkDocument> searchNativeHybrid(
      HybridSearchCommand command,
      LexicalQuery lexicalQuery,
      float[] queryVector,
      Long workspaceId,
      boolean onlyPublic,
      List<Long> allowedDocIds,
      int limit) {
    try {
      String queryText = lexicalQuery != null ? lexicalQuery.cleanedQuery() : command.query();
      NativeQueryBuilder builder = NativeQuery.builder();

      // 路径 1: BM25 词法全文检索 + 过滤条件
      builder.withQuery(
          q ->
              q.bool(
                  b -> {
                    b.must(
                        m ->
                            m.multiMatch(
                                mm ->
                                    mm.query(queryText)
                                        .type(TextQueryType.MostFields)
                                        .tieBreaker(0.3)
                                        .fields(
                                            List.of(
                                                "content^4",
                                                "title^3",
                                                "headerPath^2",
                                                "contextPrefix^0.5"))));

                    applyCommonFilters(b, command, workspaceId, onlyPublic, allowedDocIds);
                    return b;
                  }));

      // 路径 2: kNN 密集向量检索 + 过滤条件
      if (queryVector != null && queryVector.length > 0) {
        List<Float> vectorList = new ArrayList<>(queryVector.length);
        for (float f : queryVector) {
          vectorList.add(f);
        }
        builder.withKnnSearches(
            knn -> {
              knn.field("content_vector")
                  .queryVector(vectorList)
                  .k(limit)
                  .numCandidates(Math.max(limit * 2, 50))
                  .filter(
                      f ->
                          f.bool(
                              b -> {
                                applyCommonFilters(
                                    b, command, workspaceId, onlyPublic, allowedDocIds);
                                return b;
                              }));
              return knn;
            });
      }

      builder.withPageable(PageRequest.of(0, limit));
      SearchHits<DocChunkDocument> hits =
          elasticsearchOperations.search(builder.build(), DocChunkDocument.class);

      return extractDocuments(hits);
    } catch (Exception e) {
      log.warn(
          "【HybridSearch】ES 原生双路混合检索异常: workspaceId={}, query=[{}], error={}",
          workspaceId,
          command.query(),
          e.getMessage());
      return List.of();
    }
  }

  private void applyCommonFilters(
      BoolQuery.Builder b,
      HybridSearchCommand command,
      Long workspaceId,
      boolean onlyPublic,
      List<Long> allowedDocIds) {

    // Filter 1: 工作区隔离过滤器
    b.filter(f -> f.term(t -> t.field("workspaceId").value(String.valueOf(workspaceId))));

    // Filter 2: 可选文档范围过滤
    if (command.documentId() != null) {
      b.filter(f -> f.term(t -> t.field("documentId").value(String.valueOf(command.documentId()))));
    }

    // Filter 3: 可选分类范围过滤
    if (command.categoryId() != null) {
      b.filter(f -> f.term(t -> t.field("categoryId").value(String.valueOf(command.categoryId()))));
    }

    // Filter 4: 可选标签精确过滤
    if (command.tags() != null && !command.tags().isEmpty()) {
      List<FieldValue> tagValues = command.tags().stream().map(FieldValue::of).toList();
      b.filter(f -> f.terms(ts -> ts.field("tags").terms(t -> t.value(tagValues))));
    }

    // Filter 5: 文档可见性与租户细粒度 ACL 过滤 (优先应用细粒度授权文档名单)
    if (allowedDocIds != null && !allowedDocIds.isEmpty()) {
      List<FieldValue> docIdValues =
          allowedDocIds.stream().map(String::valueOf).map(FieldValue::of).toList();
      b.filter(f -> f.terms(ts -> ts.field("documentId").terms(t -> t.value(docIdValues))));
    } else if (onlyPublic) {
      b.filter(f -> f.term(t -> t.field("visibility").value("PUBLIC")));
    } else {
      applyAclFilter(b, command);
    }
  }

  private void applyAclFilter(BoolQuery.Builder b, HybridSearchCommand command) {
    if (command.isAdmin()) {
      return;
    }

    b.filter(
        af ->
            af.bool(
                ab -> {
                  // 1. PUBLIC 或 INTERNAL 可见
                  ab.should(
                      s ->
                          s.terms(
                              ts ->
                                  ts.field("visibility")
                                      .terms(
                                          t ->
                                              t.value(
                                                  List.of(
                                                      FieldValue.of("PUBLIC"),
                                                      FieldValue.of("INTERNAL"))))));

                  // 2. 自己创建的 PRIVATE 文档
                  if (command.userId() != null) {
                    ab.should(
                        s ->
                            s.bool(
                                pb ->
                                    pb.filter(
                                            pf ->
                                                pf.term(
                                                    t -> t.field("visibility").value("PRIVATE")))
                                        .filter(
                                            pf ->
                                                pf.term(
                                                    t ->
                                                        t.field("createBy")
                                                            .value(
                                                                String.valueOf(
                                                                    command.userId()))))));
                  }

                  // 3. 兼容历史数据：若切片未落库 visibility 字段，则默认允许工作区成员访问
                  ab.should(
                      s -> s.bool(hb -> hb.mustNot(mn -> mn.exists(e -> e.field("visibility")))));

                  ab.minimumShouldMatch("1");
                  return ab;
                }));
  }

  private List<DocChunkDocument> extractDocuments(SearchHits<DocChunkDocument> hits) {
    if (hits == null || hits.isEmpty()) {
      return List.of();
    }
    return hits.getSearchHits().stream()
        .map(
            hit -> {
              DocChunkDocument doc = hit.getContent();
              if (doc != null && !Float.isNaN(hit.getScore())) {
                doc.setRawScore(hit.getScore());
              }
              return doc;
            })
        .toList();
  }
}
