package io.lexiloom.application.document.parser;

import java.util.LinkedHashMap;
import java.util.Map;

/** DeepDoc 版面解析后的统一中间块，尚未进入最终检索切块阶段。 */
public record DeepDocBlock(
    String blockId,
    int sequence,
    String blockType,
    String content,
    Integer page,
    DocBoxMetadata boxMetadata,
    String anchor,
    String source) {

  public DeepDocBlock {
    blockId = blockId != null ? blockId : "";
    blockType = blockType != null ? blockType : "text";
    content = content != null ? content : "";
    source = source != null ? source : "deepdoc";
  }

  public DocTextChunk toTextChunk() {
    return new DocTextChunk(blockId, content, blockType, page, boxMetadata, anchor);
  }

  public Map<String, Object> toMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("block_id", blockId);
    map.put("sequence", sequence);
    map.put("block_type", blockType);
    map.put("content", content);
    if (page != null) map.put("page", page);
    if (boxMetadata != null) map.put("bbox", boxMetadata.box());
    if (anchor != null) map.put("anchor", anchor);
    map.put("source", source);
    return Map.copyOf(map);
  }
}
