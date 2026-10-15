package io.lexiloom.application.document.parser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 单行/单段切片文本块及其绑定的版面元数据 (VO)
 *
 * <p>兼容 DeepDoc 全格式切片模型（PDF 坐标段落、Word 段落/表格、PPT 幻灯片、Excel 工作表、纯文本）。
 *
 * @param chunkId 切片唯一标识（如 chunk_p_0, docx_p_1, docx_tbl_0, ppt_slide_1, excel_sheet_1 等）
 * @param content 切片干净正文内容
 * @param type 切片类型（text / table / slide 等）
 * @param page PDF / PPT 对应的页码，若无则为 null
 * @param boxMetadata PDF 页面上对应的矩形边界盒 [x0, y0, x1, y1]，若无则为 null
 * @param anchor 定位锚点（如 "段落_1", "表格_1", "第_1_页幻灯片", "工作表/表格_1"）
 */
public record DocTextChunk(
    String chunkId,
    String content,
    String type,
    Integer page,
    DocBoxMetadata boxMetadata,
    String anchor) {

  public DocTextChunk {
    chunkId = chunkId != null ? chunkId : "";
    content = content != null ? content : "";
    type = type != null ? type : "text";
  }

  /** 兼容原有双参构造器：仅传入 text 与 boxMetadata */
  public DocTextChunk(String text, DocBoxMetadata boxMetadata) {
    this("", text, "text", boxMetadata != null ? boxMetadata.page() : null, boxMetadata, null);
  }

  /** 兼容原有 text 访问器方法 */
  public String text() {
    return content;
  }

  /** 获取页码 Optional */
  public Optional<Integer> findPage() {
    return Optional.ofNullable(page);
  }

  /** 获取坐标元数据的 Optional 包装 */
  public Optional<DocBoxMetadata> findBoxMetadata() {
    return Optional.ofNullable(boxMetadata);
  }

  /** 获取定位锚点 Optional */
  public Optional<String> findAnchor() {
    return Optional.ofNullable(anchor);
  }

  /** 是否包含坐标元数据 */
  public boolean hasMetadata() {
    return boxMetadata != null;
  }

  /** 转换为结构化 Map 对象（字段与 Python 服务保持一致） */
  public Map<String, Object> toMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    if (!chunkId.isEmpty()) {
      map.put("chunk_id", chunkId);
    }
    map.put("content", content);
    map.put("type", type);
    if (page != null) {
      map.put("page", page);
    }
    if (boxMetadata != null) {
      map.put("bbox", boxMetadata.box());
    }
    if (anchor != null && !anchor.isEmpty()) {
      map.put("anchor", anchor);
    }
    return Map.copyOf(map);
  }
}
