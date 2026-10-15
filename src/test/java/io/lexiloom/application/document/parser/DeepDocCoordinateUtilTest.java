package io.lexiloom.application.document.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DeepDocCoordinateUtil 坐标解析与清洗工具类测试")
class DeepDocCoordinateUtilTest {

  @Test
  @DisplayName("验证用户用例：准确提取坐标为 [x0, y0, x1, y1] 并彻底抹除坐标标记得到干净文本")
  void shouldExtractUserCaseAccurately() {
    String rawLine = "优点：简单，最多就需要...并且包含@@1\t36.3\t548.0\t271.0\t286.0##";

    DocCoordinateParseResult result = DeepDocCoordinateUtil.parse(rawLine);

    // 1. 验证干净文本
    assertThat(result.cleanText()).isEqualTo("优点：简单，最多就需要...并且包含");

    // 2. 验证坐标元数据
    assertThat(result.hasMetadata()).isTrue();
    Optional<DocBoxMetadata> optMeta = result.findBoxMetadata();
    assertThat(optMeta).isPresent();

    DocBoxMetadata meta = optMeta.get();
    assertThat(meta.page()).isEqualTo(1);
    // 对应 Python: "box": [float(x0), float(y0), float(x1), float(y1)]
    // x0=36.3, y0=271.0, x1=548.0, y1=286.0
    assertThat(meta.box()).containsExactly(36.3, 271.0, 548.0, 286.0);
    assertThat(meta.x0()).isEqualTo(36.3);
    assertThat(meta.y0()).isEqualTo(271.0);
    assertThat(meta.x1()).isEqualTo(548.0);
    assertThat(meta.y1()).isEqualTo(286.0);

    // 3. 验证与 Python 输出一致的字典结构
    Map<String, Object> map = meta.toMap();
    assertThat(map).containsEntry("page", 1);
    assertThat(map).containsEntry("box", List.of(36.3, 271.0, 548.0, 286.0));
  }

  @Test
  @DisplayName("验证仅清洗方法 clean() 抹除坐标标记")
  void shouldCleanTextOnly() {
    String rawLine = "优点：简单，最多就需要...并且包含@@1\t36.3\t548.0\t271.0\t286.0##";
    String clean = DeepDocCoordinateUtil.clean(rawLine);
    assertThat(clean).isEqualTo("优点：简单，最多就需要...并且包含");
  }

  @Test
  @DisplayName("验证 extractBoxMetadataMap() 输出与 Python 字典一致的结构")
  void shouldExtractBoxMetadataMap() {
    String rawLine = "正文内容@@2\t10.5\t200.0\t30.5\t80.0##";
    Optional<Map<String, Object>> mapOpt = DeepDocCoordinateUtil.extractBoxMetadataMap(rawLine);
    assertThat(mapOpt).isPresent();
    Map<String, Object> map = mapOpt.get();
    assertThat(map.get("page")).isEqualTo(2);
    assertThat(map.get("box")).isEqualTo(List.of(10.5, 30.5, 200.0, 80.0));
  }

  @Test
  @DisplayName("验证多标记全文 parseAll() 提取多个坐标并彻底抹除所有标记")
  void shouldParseAllMultipleBoxes() {
    String rawText =
        """
        段落一@@1\t10.0\t20.0\t30.0\t40.0##
        段落二@@2\t50.0\t60.0\t70.0\t80.0##
        """;

    DocCoordinateMultiParseResult multi = DeepDocCoordinateUtil.parseAll(rawText);

    assertThat(multi.cleanText()).isEqualTo("段落一\n段落二");
    assertThat(multi.boxes()).hasSize(2);

    DocBoxMetadata first = multi.boxes().get(0);
    assertThat(first.page()).isEqualTo(1);
    assertThat(first.box()).containsExactly(10.0, 30.0, 20.0, 40.0);

    DocBoxMetadata second = multi.boxes().get(1);
    assertThat(second.page()).isEqualTo(2);
    assertThat(second.box()).containsExactly(50.0, 70.0, 60.0, 80.0);
  }

  @Test
  @DisplayName("支持空格作为分隔符的容错解析")
  void shouldSupportSpaceSeparators() {
    String raw = "测试空格分隔@@3 12.0 34.0 56.0 78.0##";
    DocCoordinateParseResult result = DeepDocCoordinateUtil.parse(raw);
    assertThat(result.cleanText()).isEqualTo("测试空格分隔");
    assertThat(result.hasMetadata()).isTrue();
    assertThat(result.boxMetadata().page()).isEqualTo(3);
    assertThat(result.boxMetadata().box()).containsExactly(12.0, 56.0, 34.0, 78.0);
  }

  @Test
  @DisplayName("无坐标标记时应安全返回原文本且元数据为空")
  void shouldHandleTextWithoutCoordinates() {
    String normal = "没有任何坐标的常规正文";
    DocCoordinateParseResult result = DeepDocCoordinateUtil.parse(normal);
    assertThat(result.cleanText()).isEqualTo("没有任何坐标的常规正文");
    assertThat(result.hasMetadata()).isFalse();
    assertThat(result.findBoxMetadata()).isEmpty();

    assertThat(DeepDocCoordinateUtil.containsCoordinate(normal)).isFalse();
    assertThat(DeepDocCoordinateUtil.extractFirstBox(normal)).isEmpty();
    assertThat(DeepDocCoordinateUtil.extractAllBoxes(normal)).isEmpty();
  }

  @Test
  @DisplayName("空值与空白输入安全处理")
  void shouldHandleNullAndBlankSafely() {
    assertThat(DeepDocCoordinateUtil.clean(null)).isEqualTo("");
    assertThat(DeepDocCoordinateUtil.clean("   ")).isEqualTo("");
    assertThat(DeepDocCoordinateUtil.parse(null).cleanText()).isEqualTo("");
    assertThat(DeepDocCoordinateUtil.parse(null).hasMetadata()).isFalse();
    assertThat(DeepDocCoordinateUtil.parseAll(null).cleanText()).isEqualTo("");
    assertThat(DeepDocCoordinateUtil.parseAll(null).boxes()).isEmpty();
    assertThat(DeepDocCoordinateUtil.extractFirstBox(null)).isEmpty();
    assertThat(DeepDocCoordinateUtil.extractAllBoxes(null)).isEmpty();
    assertThat(DeepDocCoordinateUtil.containsCoordinate(null)).isFalse();
  }

  @Test
  @DisplayName("验证 formatCoordinateTag 格式化及逆向还原往返一致性")
  void shouldFormatAndRoundTripCoordinateTag() {
    DocBoxMetadata meta = DocBoxMetadata.of(1, 36.3, 271.0, 548.0, 286.0);
    String formattedTag = DeepDocCoordinateUtil.formatCoordinateTag(meta);
    assertThat(formattedTag).isEqualTo("@@1\t36.3\t548.0\t271.0\t286.0##");

    // 往返解析测试
    DocCoordinateParseResult roundTrip = DeepDocCoordinateUtil.parse("前缀" + formattedTag);
    assertThat(roundTrip.cleanText()).isEqualTo("前缀");
    assertThat(roundTrip.boxMetadata()).isEqualTo(meta);
  }

  @Test
  @DisplayName("验证 DocBoxMetadata 参数校验与不可变性")
  void shouldValidateDocBoxMetadata() {
    assertThatThrownBy(() -> new DocBoxMetadata(1, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("box 坐标列表不能为空");

    assertThatThrownBy(() -> new DocBoxMetadata(1, List.of(1.0, 2.0, 3.0)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须包含且仅包含 4 个元素");

    DocBoxMetadata valid = DocBoxMetadata.of(1, 10.0, 20.0, 30.0, 40.0);
    assertThatThrownBy(() -> valid.box().add(50.0))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("验证解析真实的 DeepDoc JSON 响应：从 data 数组中提取文本、清洗坐标并获取结构化元数据")
  void shouldParseRealDeepDocJsonResponse() {
    String json =
        """
        {
          "code": 200,
          "filename": "码流验证工具集成方案.pdf",
          "markdown": "",
          "data": [
            "码流⼯具集成到S16验证@@1\\t38.0\\t313.3\\t43.0\\t66.7##\\n⼯作流程为，调⽤可执⾏程序通过命令输⼊码流，执⾏结果通过标准输出写⼊⽂件，然后@@1\\t37.3\\t549.0\\t94.7\\t109.7##\\n\\n程序读取⽂件中的解析结果，删除该临时⽂件@@1\\t36.3\\t297.3\\t114.0\\t129.0##",
            []
          ]
        }
        """;

    DeepDocParsedDocument doc = DeepDocCoordinateUtil.parseResponse(json);

    assertThat(doc.code()).isEqualTo(200);
    assertThat(doc.filename()).isEqualTo("码流验证工具集成方案.pdf");
    // 验证正文中的坐标已被清洗
    assertThat(doc.markdown()).doesNotContain("@@");
    assertThat(doc.markdown()).doesNotContain("##");
    assertThat(doc.markdown()).contains("码流⼯具集成到S16验证");
    assertThat(doc.markdown()).contains("程序读取⽂件中的解析结果，删除该临时⽂件");

    // 验证坐标提取
    assertThat(doc.boxes()).hasSize(3);
    DocBoxMetadata firstBox = doc.boxes().get(0);
    assertThat(firstBox.page()).isEqualTo(1);
    assertThat(firstBox.box()).containsExactly(38.0, 43.0, 313.3, 66.7);

    // 验证细粒度 chunks
    assertThat(doc.chunks()).hasSize(3);
    DocTextChunk chunk1 = doc.chunks().get(0);
    assertThat(chunk1.text()).isEqualTo("码流⼯具集成到S16验证");
    assertThat(chunk1.hasMetadata()).isTrue();
    assertThat(chunk1.boxMetadata().page()).isEqualTo(1);
  }

  @Test
  @DisplayName("验证解析最新 Multi-Format 结构化响应：包含 file_type, total_chunks, markdown 与 chunks 列表")
  void shouldParseNewMultiFormatJsonResponse() {
    String json =
        """
        {
          "code": 200,
          "filename": "集成方案.docx",
          "file_type": "docx",
          "total_chunks": 3,
          "markdown": "第一段正文\\n\\n| 列1 | 列2 |\\n| --- | --- |\\n| 数据1 | 数据2 |\\n\\n第二段正文",
          "chunks": [
            {
              "chunk_id": "docx_p_0",
              "content": "第一段正文",
              "type": "text",
              "anchor": "段落_1"
            },
            {
              "chunk_id": "docx_tbl_0",
              "content": "| 列1 | 列2 |\\n| --- | --- |\\n| 数据1 | 数据2 |",
              "type": "table",
              "anchor": "表格_1"
            },
            {
              "chunk_id": "docx_p_1",
              "content": "第二段正文",
              "type": "text",
              "anchor": "段落_2"
            }
          ]
        }
        """;

    DeepDocParsedDocument doc = DeepDocCoordinateUtil.parseResponse(json);

    assertThat(doc.code()).isEqualTo(200);
    assertThat(doc.filename()).isEqualTo("集成方案.docx");
    assertThat(doc.fileType()).isEqualTo("docx");
    assertThat(doc.totalChunks()).isEqualTo(3);
    assertThat(doc.markdown()).contains("第一段正文");
    assertThat(doc.markdown()).contains("| 列1 | 列2 |");
    assertThat(doc.chunks()).hasSize(3);

    DocTextChunk chunk0 = doc.chunks().get(0);
    assertThat(chunk0.chunkId()).isEqualTo("docx_p_0");
    assertThat(chunk0.content()).isEqualTo("第一段正文");
    assertThat(chunk0.type()).isEqualTo("text");
    assertThat(chunk0.anchor()).isEqualTo("段落_1");

    DocTextChunk chunk1 = doc.chunks().get(1);
    assertThat(chunk1.chunkId()).isEqualTo("docx_tbl_0");
    assertThat(chunk1.type()).isEqualTo("table");
    assertThat(chunk1.anchor()).isEqualTo("表格_1");
  }

  @Test
  @DisplayName("验证解析最新 PDF 多格式结构化响应：含 page 与 bbox 数组")
  void shouldParseNewPdfMultiFormatResponseWithBbox() {
    String json =
        """
        {
          "code": 200,
          "filename": "报告.pdf",
          "file_type": "pdf",
          "total_chunks": 2,
          "markdown": "测试段落内容\\n\\n表格内容",
          "chunks": [
            {
              "chunk_id": "chunk_p_0",
              "content": "测试段落内容",
              "type": "text",
              "page": 1,
              "bbox": [36.3, 271.0, 548.0, 286.0]
            },
            {
              "chunk_id": "chunk_tbl_0",
              "content": "表格内容",
              "type": "table"
            }
          ]
        }
        """;

    DeepDocParsedDocument doc = DeepDocCoordinateUtil.parseResponse(json);

    assertThat(doc.code()).isEqualTo(200);
    assertThat(doc.filename()).isEqualTo("报告.pdf");
    assertThat(doc.fileType()).isEqualTo("pdf");
    assertThat(doc.totalChunks()).isEqualTo(2);
    assertThat(doc.boxes()).hasSize(1);

    DocBoxMetadata box = doc.boxes().get(0);
    assertThat(box.page()).isEqualTo(1);
    assertThat(box.box()).containsExactly(36.3, 271.0, 548.0, 286.0);

    DocTextChunk chunk0 = doc.chunks().get(0);
    assertThat(chunk0.chunkId()).isEqualTo("chunk_p_0");
    assertThat(chunk0.page()).isEqualTo(1);
    assertThat(chunk0.hasMetadata()).isTrue();
    assertThat(chunk0.boxMetadata().box()).containsExactly(36.3, 271.0, 548.0, 286.0);
    assertThat(chunk0.toMap()).containsEntry("bbox", List.of(36.3, 271.0, 548.0, 286.0));
  }
}
