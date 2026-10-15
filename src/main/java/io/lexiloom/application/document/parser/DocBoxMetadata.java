package io.lexiloom.application.document.parser;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * PDF 版面元素结构化坐标元数据 (VO)
 *
 * <p>对应 RAGFlow / DeepDoc 提取的 {@code @@页码\tx0\tx1\ty0\ty1##} 格式， 标准化为 {@code [x0, y0, x1, y1]} (即
 * {@code [left, top, right, bottom]}) 矩形边界盒坐标。
 *
 * @param page PDF 页码（从 1 开始）
 * @param box 边界盒矩形坐标列表 [x0, y0, x1, y1]
 */
public record DocBoxMetadata(int page, List<Double> box) {

  public DocBoxMetadata {
    Objects.requireNonNull(box, "box 坐标列表不能为空");
    if (box.size() != 4) {
      throw new IllegalArgumentException("box 坐标列表必须包含且仅包含 4 个元素 [x0, y0, x1, y1]");
    }
    box = List.copyOf(box);
  }

  /**
   * 静态工厂方法：通过页码与四隅坐标重组构造
   *
   * @param page 页码
   * @param x0 左边界 (min_x)
   * @param y0 上边界 (min_y)
   * @param x1 右边界 (max_x)
   * @param y1 下边界 (max_y)
   * @return 坐标元数据对象
   */
  public static DocBoxMetadata of(int page, double x0, double y0, double x1, double y1) {
    return new DocBoxMetadata(page, List.of(x0, y0, x1, y1));
  }

  /** 左边界 x0 (min_x) */
  public double x0() {
    return box.get(0);
  }

  /** 上边界 y0 (min_y) */
  public double y0() {
    return box.get(1);
  }

  /** 右边界 x1 (max_x) */
  public double x1() {
    return box.get(2);
  }

  /** 下边界 y1 (max_y) */
  public double y1() {
    return box.get(3);
  }

  /**
   * 转为符合 Python/JSON 规范的元数据字典映射:
   *
   * <pre>{@code
   * {"page": 1, "box": [36.3, 271.0, 548.0, 286.0]}
   * }</pre>
   */
  public Map<String, Object> toMap() {
    return Map.of("page", page, "box", box);
  }
}
