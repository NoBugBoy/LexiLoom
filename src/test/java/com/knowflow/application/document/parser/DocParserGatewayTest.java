package com.knowflow.application.document.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.knowflow.application.exception.BusinessException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocParserGatewayTest {

  private DocParserEngine deepDocEngine;

  @BeforeEach
  void setUp() {
    deepDocEngine = mock(DocParserEngine.class);
    when(deepDocEngine.getEngineType()).thenReturn(DocParserEngineEnum.DEEPDOC);
    when(deepDocEngine.supports("pdf")).thenReturn(true);
    when(deepDocEngine.supports("docx")).thenReturn(true);
    when(deepDocEngine.supports("unknown")).thenReturn(false);
  }

  @Test
  @DisplayName("AUTO 模式下默认优先使用 DeepDoc 引擎")
  void testAutoDefaultsToDeepDoc() {
    when(deepDocEngine.isAvailable()).thenReturn(true);
    when(deepDocEngine.parse(any()))
        .thenReturn(
            DocParseResult.success(
                "md", "raw", "DeepDocTitle", DocParserEngineEnum.DEEPDOC, Map.of()));

    DefaultDocParserGateway gateway = new DefaultDocParserGateway(List.of(deepDocEngine));

    DocParseCommand command =
        DocParseCommand.ofStream(
            new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)),
            "annual_report.pdf",
            DocParserEngineEnum.AUTO);

    DocParseResult result = gateway.parse(command);
    assertThat(result.engineUsed()).isEqualTo(DocParserEngineEnum.DEEPDOC);
    assertThat(result.suggestedTitle()).isEqualTo("DeepDocTitle");
  }

  @Test
  @DisplayName("手动指定 DEEPDOC 引擎时正常调用")
  void testManualSpecifiedDeepDocPreferred() {
    when(deepDocEngine.isAvailable()).thenReturn(true);
    when(deepDocEngine.parse(any()))
        .thenReturn(
            DocParseResult.success(
                "md", "raw", "DeepDocTitle", DocParserEngineEnum.DEEPDOC, Map.of()));

    DefaultDocParserGateway gateway = new DefaultDocParserGateway(List.of(deepDocEngine));

    DocParseCommand command =
        DocParseCommand.ofStream(
            new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)),
            "test.pdf",
            DocParserEngineEnum.DEEPDOC);

    DocParseResult result = gateway.parse(command);
    assertThat(result.engineUsed()).isEqualTo(DocParserEngineEnum.DEEPDOC);
  }

  @Test
  @DisplayName("启动自检：未配置任何可用引擎时，启动报错提示配置 DeepDoc")
  void testStartupValidationFailsWhenNoneConfigured() {
    when(deepDocEngine.isAvailable()).thenReturn(false);

    DefaultDocParserGateway gateway = new DefaultDocParserGateway(List.of(deepDocEngine));

    assertThatThrownBy(gateway::validateEngines)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("系统未配置任何文档解析引擎服务！请配置 DeepDoc");
  }

  @Test
  @DisplayName("启动自检：配置了 DeepDoc 引擎时，启动正常通过")
  void testStartupValidationPassesWhenDeepDocConfigured() {
    when(deepDocEngine.isAvailable()).thenReturn(true);

    DefaultDocParserGateway gateway = new DefaultDocParserGateway(List.of(deepDocEngine));

    gateway.validateEngines(); // 正常无异常抛出
  }

  @Test
  @DisplayName("格式支持检查")
  void testSupportsExtension() {
    DefaultDocParserGateway gateway = new DefaultDocParserGateway(List.of(deepDocEngine));

    assertThat(gateway.supportsExtension("pdf")).isTrue();
    assertThat(gateway.supportsExtension("docx")).isTrue();
    assertThat(gateway.supportsExtension("unknown")).isFalse();
    assertThat(gateway.supportsExtension("")).isFalse();
    assertThat(gateway.supportsExtension(null)).isFalse();
  }

  @Test
  @DisplayName("未传入扩展名时抛出业务异常")
  void testParseWithoutExtensionThrows() {
    DefaultDocParserGateway gateway = new DefaultDocParserGateway(List.of(deepDocEngine));

    DocParseCommand command =
        DocParseCommand.of(
            new ByteArrayInputStream(new byte[] {1, 2, 3}),
            null,
            "no_extension",
            null,
            DocParserEngineEnum.AUTO,
            Map.of());

    assertThatThrownBy(() -> gateway.parse(command))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("无法识别的文件扩展名");
  }

  @Test
  @DisplayName("传入 null 命令时抛出业务异常")
  void testParseNullCommandThrows() {
    DefaultDocParserGateway gateway = new DefaultDocParserGateway(List.of(deepDocEngine));

    assertThatThrownBy(() -> gateway.parse(null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("解析命令不能为空");
  }
}
