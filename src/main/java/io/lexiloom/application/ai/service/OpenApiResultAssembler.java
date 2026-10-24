package io.lexiloom.application.ai.service;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.ai.gateway.rerank.ScoredDocChunkVO;
import io.lexiloom.application.ai.model.search.DocChunkDocument;
import io.lexiloom.application.ai.model.vo.CitationVO;
import io.lexiloom.application.ai.model.vo.McpChunkResultVO;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * OpenAPI 切片召回结果与视觉坐标格式化装配器 (Assembler)
 *
 * <p>将底层召回切片领域实体组装为对外 OpenAPI 视图对象 (VO) 与链路溯源引用快照。
 */
@Component
public class OpenApiResultAssembler {

  /**
   * 将打分切片列表装配为 OpenAPI 切片视图列表
   */
  public List<McpChunkResultVO> assembleChunkResults(
      List<ScoredDocChunkVO> scoredChunks, Map<Long, String> categoryNames) {
    if (scoredChunks == null || scoredChunks.isEmpty()) {
      return List.of();
    }

    return scoredChunks.stream()
        .map(
            item -> {
              DocChunkDocument chunk = item.chunk();
              if (chunk == null) {
                return null;
              }
              String chunkId =
                  StrUtil.isNotBlank(chunk.getChunkId()) ? chunk.getChunkId() : chunk.getId();
              Long docId = parseLongSafe(chunk.getDocumentId());
              String title = StrUtil.blankToDefault(chunk.getTitle(), "未知文档");
              Long catId = parseLongSafe(chunk.getCategoryId());
              String catName =
                  (catId != null && catId > 0)
                      ? categoryNames.getOrDefault(catId, "未分类")
                      : "未分类";
              String coords = formatBboxToCoordinates(chunk.getBbox());

              return new McpChunkResultVO(
                  chunkId,
                  docId,
                  title,
                  catName,
                  chunk.getContent(),
                  item.score(),
                  chunk.getChunkIndex(),
                  chunk.getPage(),
                  coords);
            })
        .filter(Objects::nonNull)
        .toList();
  }

  /**
   * 将打分切片列表构建为溯源引用快照列表
   */
  public List<CitationVO> assembleCitations(List<ScoredDocChunkVO> scoredChunks) {
    if (scoredChunks == null || scoredChunks.isEmpty()) {
      return List.of();
    }

    List<CitationVO> citations = new ArrayList<>(scoredChunks.size());
    for (int i = 0; i < scoredChunks.size(); i++) {
      ScoredDocChunkVO item = scoredChunks.get(i);
      DocChunkDocument c = item.chunk();
      if (c != null) {
        citations.add(
            new CitationVO(
                i + 1,
                parseLongSafe(c.getDocumentId()),
                c.getTitle(),
                c.getHeaderPath(),
                c.getContent(),
                item.score(),
                c.getPage(),
                c.getBbox(),
                c.getChunkType(),
                c.getAnchor(),
                item.recallSource()));
      }
    }
    return citations;
  }

  /**
   * 将 DeepDoc 四元组边界框 [x0, y0, x1, y1] 格式化为前端标准坐标 JSON 字符串
   */
  public String formatBboxToCoordinates(@Nullable List<Float> bbox) {
    if (bbox == null || bbox.size() < 4) {
      return "[]";
    }
    float x0 = bbox.get(0);
    float y0 = bbox.get(1);
    float x1 = bbox.get(2);
    float y1 = bbox.get(3);
    float w = Math.max(0, x1 - x0);
    float h = Math.max(0, y1 - y0);

    if (x0 == (long) x0 && y0 == (long) y0 && w == (long) w && h == (long) h) {
      return String.format(
          Locale.US,
          "[{\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d}]",
          (long) x0,
          (long) y0,
          (long) w,
          (long) h);
    }
    return String.format(
        Locale.US, "[{\"x\":%.2f,\"y\":%.2f,\"w\":%.2f,\"h\":%.2f}]", x0, y0, w, h);
  }

  public Long parseLongSafe(@Nullable String str) {
    if (StrUtil.isBlank(str)) {
      return null;
    }
    try {
      return Long.parseLong(str.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
