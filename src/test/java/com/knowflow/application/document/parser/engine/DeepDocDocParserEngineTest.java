package com.knowflow.application.document.parser.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowflow.application.document.parser.DocParseCommand;
import com.knowflow.application.document.parser.DocParseResult;
import com.knowflow.application.document.parser.DocParserEngineEnum;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("DeepDocDocParserEngine RAGFlow DeepDoc 解析引擎测试")
class DeepDocDocParserEngineTest {

  @Test
  @DisplayName("基本属性与格式支持校验")
  void testBasicPropertiesAndSupports() {
    DeepDocDocParserEngine engine = new DeepDocDocParserEngine();

    assertThat(engine.getEngineType()).isEqualTo(DocParserEngineEnum.DEEPDOC);
    assertThat(engine.supports("pdf")).isTrue();
    assertThat(engine.supports("PDF")).isTrue();
    assertThat(engine.supports("docx")).isTrue();
    assertThat(engine.supports("DOCX")).isTrue();
    assertThat(engine.supports("pptx")).isTrue();
    assertThat(engine.supports("xlsx")).isTrue();
    assertThat(engine.supports("xls")).isTrue();
    assertThat(engine.supports("txt")).isTrue();
    assertThat(engine.supports("md")).isTrue();
    assertThat(engine.supports("exe")).isFalse();
    assertThat(engine.supports("")).isFalse();
    assertThat(engine.supports(null)).isFalse();
  }

  @Test
  @DisplayName("未配置服务端点时应返回不可用及失败提示")
  void testNotAvailableWhenEndpointMissing() {
    DeepDocDocParserEngine engine = new DeepDocDocParserEngine();
    assertThat(engine.isAvailable()).isFalse();

    DocParseCommand command =
        DocParseCommand.ofStream(
            new ByteArrayInputStream("fake-pdf-bytes".getBytes(StandardCharsets.UTF_8)),
            "sample.pdf",
            DocParserEngineEnum.DEEPDOC);

    DocParseResult result = engine.parse(command);
    assertThat(result.isFailure()).isTrue();
    assertThat(result.errorMessage()).contains("DeepDoc 远程服务端点未配置");
  }

  @Test
  @DisplayName("配置服务端点后应标记为可用状态")
  void testAvailableWhenEndpointConfigured() {
    DeepDocDocParserEngine engine = new DeepDocDocParserEngine();
    ReflectionTestUtils.setField(engine, "deepdocEndpoint", "http://localhost:8001");

    assertThat(engine.isAvailable()).isTrue();
  }

  @Test
  @DisplayName("验证 transferFiguresToStorage 空保护与无插图快速返回")
  void testTransferFiguresNoOp() {
    DeepDocDocParserEngine engine = new DeepDocDocParserEngine();
    assertThat(engine.transferFiguresToStorage("纯文本内容")).isEqualTo("纯文本内容");
    assertThat(engine.transferFiguresToStorage("")).isEqualTo("");
    assertThat(engine.transferFiguresToStorage(null)).isNull();
  }
}
