package io.lexiloom.application.document.api;


import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DocumentContentCleaner 文档内容清洗器测试")
class DocumentContentCleanerTest {

  @Test
  @DisplayName("应成功过滤 Markdown 占位符及各种 Base64 图片标签")
  void shouldFilterMarkdownBase64Images() {
    String input =
        """
        # S16使用手册

        进入RTE设计系统，在脚本管理功能中点击新建。

        ![](data:image/png;base64...)

        ![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAA...)

        * 名称：随意填写
        """;

    String result = DocumentContentCleaner.cleanEmbeddedImages(input);

    assertThat(result).doesNotContain("data:image");
    assertThat(result).doesNotContain("![");
    assertThat(result).contains("# S16使用手册");
    assertThat(result).contains("* 名称：随意填写");
    // 验证多余空行被规整为双换行
    assertThat(result).doesNotContain("\n\n\n");
  }

  @Test
  @DisplayName("应成功过滤 HTML data URI 图片与本地死链，但保留合法 HTTP/HTTPS 网络图片")
  void shouldFilterHtmlAndLocalImagesWhilePreservingHttpImages() {
    String input =
        """
        # 混合内容测试

        <img src="data:image/png;base64,xxxx" alt="内嵌图" />
        <img src='data:image/jpeg;base64,yyyy' />
        <img src="/local/avatar.png" />

        ![本地死链](media/image1.png)
        ![文件协议](file:///private/var/folders/pic.png)

        ![合法HTTPS网络图片](https://lexiloom.io/logo.png)
        ![合法HTTP网络图片](http://lexiloom.io/banner.jpg)
        [合法外链](https://lexiloom.io/docs)
        """;

    String result = DocumentContentCleaner.cleanEmbeddedImages(input);

    assertThat(result).doesNotContain("<img");
    assertThat(result).doesNotContain("data:image");
    assertThat(result).doesNotContain("media/image1.png");
    assertThat(result).doesNotContain("file:///");
    // 保留合法的 http/https 图片和超链接
    assertThat(result).contains("![合法HTTPS网络图片](https://lexiloom.io/logo.png)");
    assertThat(result).contains("![合法HTTP网络图片](http://lexiloom.io/banner.jpg)");
    assertThat(result).contains("[合法外链](https://lexiloom.io/docs)");
  }

  @Test
  @DisplayName("输入为空或纯空白时应安全返回空字符串")
  void shouldHandleBlankInputSafely() {
    assertThat(DocumentContentCleaner.cleanEmbeddedImages(null)).isEqualTo("");
    assertThat(DocumentContentCleaner.cleanEmbeddedImages("")).isEqualTo("");
    assertThat(DocumentContentCleaner.cleanEmbeddedImages("   \n\n   ")).isEqualTo("");
  }

  @Test
  @DisplayName("应准确提取纯文本供全文检索分词使用")
  void shouldExtractRawTextAccurately() {
    String input =
        """
        # 知识流架构设计

        ```java
        System.out.println("Hello");
        ```

        ![](data:image/png;base64...)
        ![网络图](https://lexiloom.io/test.png)

        请参考 [LexiLoom官方文档](https://lexiloom.io/doc) 进行**配置**。
        """;

    String rawText = DocumentContentCleaner.extractRawText(input);

    assertThat(rawText).doesNotContain("```");
    assertThat(rawText).doesNotContain("System.out.println");
    assertThat(rawText).doesNotContain("data:image");
    assertThat(rawText).doesNotContain("https://lexiloom.io");
    assertThat(rawText).contains("知识流架构设计");
    assertThat(rawText).contains("LexiLoom官方文档");
    assertThat(rawText).contains("配置");
  }

  @Test
  @DisplayName("应在提取纯文本及清洗方法中彻底过滤 DeepDoc 坐标标记")
  void shouldFilterDeepDocCoordinates() {
    String input =
        """
        # 测试章节@@1\t10.0\t20.0\t30.0\t40.0##

        正文内容描述@@1\t36.3\t548.0\t271.0\t286.0##
        """;

    String cleanMd = DocumentContentCleaner.cleanCoordinates(input);
    assertThat(cleanMd).doesNotContain("@@");
    assertThat(cleanMd).doesNotContain("##");
    assertThat(cleanMd).contains("# 测试章节");
    assertThat(cleanMd).contains("正文内容描述");

    String rawText = DocumentContentCleaner.extractRawText(input);
    assertThat(rawText).doesNotContain("@@");
    assertThat(rawText).doesNotContain("##");
    assertThat(rawText).contains("测试章节");
    assertThat(rawText).contains("正文内容描述");
  }
}
