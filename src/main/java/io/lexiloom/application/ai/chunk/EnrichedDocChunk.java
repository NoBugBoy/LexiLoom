package io.lexiloom.application.ai.chunk;

import java.util.List;

/**
 * 经过上下文帽子加冠富化后的文档原子切块值对象 (不可变 record)
 *
 * <p>坚守 DeepDoc 天然物理边界，不含任何滑动窗口合并与重叠文本。 完整携带上下文前缀、纯净原文、视觉坐标 (page, bbox) 与结构化锚点 (anchor)。
 *
 * @param chunkIndex 原子切片在文档内的绝对序号 (0-indexed)
 * @param chunkId DeepDoc 原生切片唯一标识 (如 chunk_p_0, docx_p_1)
 * @param title 归属文档标题
 * @param headerPath 章节层级面包屑导航路径 (如 "技术架构 / 存储设计 / 向量索引")
 * @param content 原子段落纯净原文 (保持 DeepDoc 原始物理边界)
 * @param contextPrefix 上下文帽子前缀文本 (格式: "[文档: 《...》] > [章节: ...] > [类型: ...]")
 * @param enrichedText 帽子前缀与纯净原文的合成富化文本 (供向量计算与全文检索)
 * @param chunkType 切片版面类型 (text, table, slide, figure 等)
 * @param page 原件所在自然页码 (1-indexed，无则为 null)
 * @param bbox 原件矩形边界框坐标 [x0, y0, x1, y1] (无则为 null)
 * @param anchor 定位锚点 (如 "段落_1", "表格_1")
 */
public record EnrichedDocChunk(
    int chunkIndex,
    String chunkId,
    String title,
    String headerPath,
    String content,
    String contextPrefix,
    String enrichedText,
    String chunkType,
    Integer page,
    List<Float> bbox,
    String anchor) {

  public static EnrichedDocChunk of(
      int chunkIndex,
      String chunkId,
      String title,
      String headerPath,
      String content,
      String contextPrefix,
      String enrichedText,
      String chunkType,
      Integer page,
      List<Float> bbox,
      String anchor) {
    return new EnrichedDocChunk(
        chunkIndex,
        chunkId,
        title,
        headerPath,
        content,
        contextPrefix,
        enrichedText,
        chunkType,
        page,
        bbox,
        anchor);
  }
}
