package io.lexiloom.application.ai.search.domain;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.model.search.DocChunkDocument;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/** 候选切片数学打分、文本意图与序列交织算法组件 */
@Slf4j
public final class CandidateMath {

  private CandidateMath() {}

  /**
   * 计算查询向量与切片向量的真实余弦相似度
   *
   * @return 相似度取值 [-1, 1]；任一侧向量缺失、为零向量或维度不一致时返回 {@code null}
   */
  public static Double cosineSimilarity(float[] queryVector, float[] chunkVector) {
    if (queryVector == null
        || queryVector.length == 0
        || chunkVector == null
        || chunkVector.length == 0) {
      return null;
    }
    if (queryVector.length != chunkVector.length) {
      log.warn(
          "【HybridSearch】查询向量与切片向量维度不一致，跳过真实相似度计算: queryDim={}, chunkDim={}",
          queryVector.length,
          chunkVector.length);
      return null;
    }

    double dot = 0.0;
    double queryNorm = 0.0;
    double chunkNorm = 0.0;
    for (int i = 0; i < queryVector.length; i++) {
      dot += (double) queryVector[i] * chunkVector[i];
      queryNorm += (double) queryVector[i] * queryVector[i];
      chunkNorm += (double) chunkVector[i] * chunkVector[i];
    }
    if (queryNorm <= 0.0 || chunkNorm <= 0.0) {
      return null;
    }
    return dot / (Math.sqrt(queryNorm) * Math.sqrt(chunkNorm));
  }

  /**
   * 对一组 BM25 候选切片的 ES 原始得分 (Lucene BM25 rawScore) 执行 Min-Max 归一化映射
   *
   * <p>将 BM25 原始打分平滑映射至 [0.40, 0.95] 区间，防止精准命中切片因纯语义余弦较低被阈值误杀。
   */
  public static Map<String, Double> normalizeBm25Scores(List<DocChunkDocument> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return Map.of();
    }

    float maxRaw = -Float.MAX_VALUE;
    float minRaw = Float.MAX_VALUE;
    boolean hasValidScore = false;

    for (DocChunkDocument doc : candidates) {
      if (doc != null && doc.getRawScore() != null && !Float.isNaN(doc.getRawScore())) {
        float score = doc.getRawScore();
        if (score > maxRaw) {
          maxRaw = score;
        }
        if (score < minRaw) {
          minRaw = score;
        }
        hasValidScore = true;
      }
    }

    if (!hasValidScore || maxRaw <= 0.0f) {
      return Map.of();
    }

    double targetMin = 0.40;
    double targetMax = 0.95;
    double scoreRange = maxRaw - minRaw;

    Map<String, Double> normalizedMap = new HashMap<>(candidates.size());
    for (DocChunkDocument doc : candidates) {
      if (doc == null || doc.getId() == null) {
        continue;
      }
      Float raw = doc.getRawScore();
      if (raw == null || Float.isNaN(raw) || raw <= 0.0f) {
        normalizedMap.put(doc.getId(), targetMin);
      } else if (scoreRange < 1e-4) {
        normalizedMap.put(doc.getId(), 0.85);
      } else {
        double norm = targetMin + (raw - minRaw) / scoreRange * (targetMax - targetMin);
        normalizedMap.put(doc.getId(), norm);
      }
    }
    return Collections.unmodifiableMap(normalizedMap);
  }

  public static boolean isTableQuery(String query) {
    if (StrUtil.isBlank(query)) {
      return false;
    }
    String lower = query.toLowerCase();
    return lower.contains("表") || lower.contains("table");
  }

  public static boolean isTableChunk(DocChunkDocument chunk) {
    if (chunk == null) {
      return false;
    }
    if ("table".equalsIgnoreCase(chunk.getChunkType())) {
      return true;
    }
    // 这种强行找平衡的逻辑禁止使用
    //    String content = chunk.getContent();
    //    if (StrUtil.isNotBlank(content)) {
    //      return content.contains("【数据表结构定义】")
    //          || (content.contains("|")
    //              && (content.contains("字段") || content.contains("类型") ||
    // content.contains("注释")));
    //    }
    return false;
  }

  /** 对双路初筛结果进行交替轮询与去重合并 */
  public static List<DocChunkDocument> interleaveAndDeduplicate(
      List<DocChunkDocument> list1, List<DocChunkDocument> list2) {
    List<DocChunkDocument> result = new ArrayList<>();
    Set<String> seenIds = new HashSet<>();
    int maxLen = Math.max(list1.size(), list2.size());

    for (int i = 0; i < maxLen; i++) {
      if (i < list1.size()) {
        DocChunkDocument doc1 = list1.get(i);
        if (doc1 != null && doc1.getId() != null && seenIds.add(doc1.getId())) {
          result.add(doc1);
        }
      }
      if (i < list2.size()) {
        DocChunkDocument doc2 = list2.get(i);
        if (doc2 != null && doc2.getId() != null && seenIds.add(doc2.getId())) {
          result.add(doc2);
        }
      }
    }
    return result;
  }

  /** 对双路初筛结果进行公平交织轮询 (为 RRF 提供对称排位流，保留重复项供 RRF 累加得分) */
  public static List<DocChunkDocument> interleaveRaw(
      List<DocChunkDocument> list1, List<DocChunkDocument> list2) {
    List<DocChunkDocument> result = new ArrayList<>(list1.size() + list2.size());
    int maxLen = Math.max(list1.size(), list2.size());
    for (int i = 0; i < maxLen; i++) {
      if (i < list1.size()) {
        DocChunkDocument doc1 = list1.get(i);
        if (doc1 != null && doc1.getId() != null) {
          result.add(doc1);
        }
      }
      if (i < list2.size()) {
        DocChunkDocument doc2 = list2.get(i);
        if (doc2 != null && doc2.getId() != null) {
          result.add(doc2);
        }
      }
    }
    return result;
  }
}
