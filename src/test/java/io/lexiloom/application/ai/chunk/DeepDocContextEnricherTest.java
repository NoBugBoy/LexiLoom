package io.lexiloom.application.ai.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import io.lexiloom.application.ai.config.AiProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DeepDocContextEnricher Token分块与滑动堆叠富化器测试")
class DeepDocContextEnricherTest {

  private AiProperties properties;
  private DeepDocContextEnricher enricher;

  @BeforeEach
  void setUp() {
    properties = new AiProperties();
    enricher = new DeepDocContextEnricher(properties);
  }

  @Test
  @DisplayName("短段落智能聚合测试：属于同一章节的连续短段落聚合为一个饱满切块，杜绝碎片化")
  void testShortParagraphAggregation() {
    String markdown =
        """
        # 总体方案设计

        第一段介绍系统背景。

        第二段说明核心目标。

        第三段阐述技术选型。
        """;

    List<EnrichedDocChunk> results = enricher.enrichFromMarkdown("技术规格书", markdown);

    // 三个短段落聚合为一个完整切块
    assertThat(results).hasSize(1);
    EnrichedDocChunk chunk = results.getFirst();
    assertThat(chunk.content()).contains("第一段介绍系统背景");
    assertThat(chunk.content()).contains("第二段说明核心目标");
    assertThat(chunk.content()).contains("第三段阐述技术选型");
    assertThat(chunk.headerPath()).isEqualTo("总体方案设计");
    assertThat(chunk.contextPrefix()).isEqualTo("[文档: 《技术规格书》] > [章节: 总体方案设计] > [类型: text]");
  }

  @Test
  @DisplayName("长文本滑动切分与堆叠测试：超出目标 Token 时优雅按句切分，后一个切块包含前一个切块尾部的重叠内容")
  void testSlidingWindowWithOverlap() {
    // 设置较小的 targetChunkTokens 和 overlapTokens 便于测试滑动窗口
    properties.getChunk().setTargetChunkTokens(25);
    properties.getChunk().setOverlapTokens(10);

    String longMarkdown =
        """
        第一句描述系统架构。第二句说明存储设计与索引优化。第三句阐述网关分流与负载均衡。第四句分析高并发与缓存一致性保障。第五句总结整体实施效益。
        """;

    List<EnrichedDocChunk> results = enricher.enrichFromMarkdown("架构方案", longMarkdown);

    // 应该切出多个切块，且相邻切块之间存在重叠堆叠 (Overlap)
    assertThat(results.size()).isGreaterThanOrEqualTo(2);

    EnrichedDocChunk firstChunk = results.get(0);
    EnrichedDocChunk secondChunk = results.get(1);

    // 验证切块存在内容且连续
    assertThat(firstChunk.content()).contains("第一句描述系统架构");
    // 验证滑动窗口：后一个切块包含前一个切块的部分过渡句子
    assertThat(secondChunk.content()).isNotEmpty();
  }

  @Test
  @DisplayName("Markdown 结构与多级大纲面包屑推导测试：# 与 ## 标题不产生孤立碎块，自动作为后续段落的章节路径")
  void testMarkdownHeaderBreadcrumbDerivation() {
    String markdown =
        """
        # 系统架构

        ## 存储设计

        LexiLoom 采用 Elasticsearch 8.x 密集向量存储。

        ## 缓存设计

        LexiLoom 采用 Redis 7.x 缓存热点元数据。
        """;

    List<EnrichedDocChunk> results = enricher.enrichFromMarkdown("架构白皮书", markdown);

    assertThat(results).hasSize(2);

    EnrichedDocChunk chunk1 = results.get(0);
    assertThat(chunk1.headerPath()).isEqualTo("系统架构 / 存储设计");
    assertThat(chunk1.content()).isEqualTo("LexiLoom 采用 Elasticsearch 8.x 密集向量存储。");
    assertThat(chunk1.contextPrefix()).isEqualTo("[文档: 《架构白皮书》] > [章节: 系统架构 / 存储设计] > [类型: text]");

    EnrichedDocChunk chunk2 = results.get(1);
    assertThat(chunk2.headerPath()).isEqualTo("系统架构 / 缓存设计");
    assertThat(chunk2.content()).isEqualTo("LexiLoom 采用 Redis 7.x 缓存热点元数据。");
  }

  @Test
  @DisplayName("特殊版面保护测试：Markdown 表格作为一个完整原子块保留，chunkType 标记为 table")
  void testTablePreservedAsIndependentChunk() {
    String markdown =
        """
        # 成本核算

        以下是服务器资源配置表：

        | 节点类型 | CPU | 内存 |
        |---|---|---|
        | ES Master | 4核 | 16G |
        | Redis | 2核 | 8G |

        正文后续总结说明。
        """;

    List<EnrichedDocChunk> results = enricher.enrichFromMarkdown("成本核算表", markdown);

    // 包含普通正文、表格原子块、后续总结
    assertThat(results.stream().anyMatch(c -> "table".equals(c.chunkType()))).isTrue();

    EnrichedDocChunk tableChunk =
        results.stream().filter(c -> "table".equals(c.chunkType())).findFirst().orElseThrow();
    assertThat(tableChunk.content()).contains("| 节点类型 | CPU | 内存 |");
    assertThat(tableChunk.content()).contains("| ES Master | 4核 | 16G |");
    assertThat(tableChunk.headerPath()).isEqualTo("成本核算");
    assertThat(tableChunk.contextPrefix()).isEqualTo("[文档: 《成本核算表》] > [章节: 成本核算] > [类型: table]");
  }

  @Test
  @DisplayName("防御性极端超长文本截断保护：超出 maxTokenLimit 时安全截断并保护系统稳定")
  void testDefensiveLongTextTruncation() {
    properties.getChunk().setMaxTokenLimit(50);

    String longContent = "A".repeat(120);
    List<EnrichedDocChunk> results = enricher.enrichFromMarkdown("超长文档", longContent);

    assertThat(results).hasSize(1);
    assertThat(results.getFirst().content()).hasSize(50);
  }

  @Test
  @DisplayName("关闭上下文帽子开关：contextPrefixEnabled=false 时直接透传原文")
  void testContextPrefixDisabled() {
    properties.getChunk().setContextPrefixEnabled(false);

    List<EnrichedDocChunk> results = enricher.enrichFromMarkdown("文档", "纯正文内容");

    assertThat(results).hasSize(1);
    assertThat(results.getFirst().contextPrefix()).isEmpty();
    assertThat(results.getFirst().enrichedText()).isEqualTo("纯正文内容");
  }

  @Test
  @DisplayName("目录行过滤测试：过滤连续目录行 (TOC)，保留有效正文")
  void testDropsConsecutiveTableOfContentsEntries() {
    String markdown =
        """
        1. 总体方案设计 ...... 2
        1.1. 系统结构设计 ...... 2
        1.2. 数据流设计 ...... 3

        系统总体方案由接入层、服务层和数据层组成。
        """;

    List<EnrichedDocChunk> results = enricher.enrichFromMarkdown("系统设计", markdown);

    assertThat(results).hasSize(1);
    assertThat(results.getFirst().content()).isEqualTo("系统总体方案由接入层、服务层和数据层组成。");
  }

  @Test
  @DisplayName("汉字异常空格自动清洗测试：清洗由于 OCR 字符切分误插入的空格")
  void testCleanCjkSpaces() {
    String markdown = "码 流 工 具 集成 到 S16 验证 ， 正常 运行 。";
    List<EnrichedDocChunk> results = enricher.enrichFromMarkdown("工具说明", markdown);

    assertThat(results).hasSize(1);
    assertThat(results.getFirst().content()).isEqualTo("码流工具集成到 S16 验证，正常运行。");
  }

  @Test
  @DisplayName("HTML 原生表格测试：表格保留为独立原子块，且 enrichedText 剥离 HTML 标签去噪")
  void testHtmlTablePreservedAndStrippedForEmbedding() {
    String htmlTable =
        """
        <table>
          <tr><th>模块名称</th><th>责任人</th><th>技术栈</th></tr>
          <tr><td>网关服务</td><td>张三</td><td>Spring Cloud Gateway</td></tr>
          <tr><td>文档解析</td><td>李四</td><td>DeepDoc & Python</td></tr>
        </table>
        """;

    List<EnrichedDocChunk> results = enricher.enrichFromMarkdown("模块分工", htmlTable);

    assertThat(results).hasSize(1);
    EnrichedDocChunk chunk = results.getFirst();
    assertThat(chunk.chunkType()).isEqualTo("table");
    // 存储与展示保留原始 HTML 结构
    assertThat(chunk.content()).contains("<table>");
    assertThat(chunk.content()).contains("<th>模块名称</th>");
    // 向量 embedding 剥离 HTML 标签去噪，避免标签稀释语义
    assertThat(chunk.enrichedText()).doesNotContain("<table>");
    assertThat(chunk.enrichedText()).doesNotContain("<th>");
    assertThat(chunk.enrichedText()).contains("模块名称");
    assertThat(chunk.enrichedText()).contains("Spring Cloud Gateway");
    assertThat(chunk.contextPrefix()).contains("[类型: table]");
  }

  @Test
  @DisplayName("超长 HTML 表格分块测试：超出目标 Token 时保留表头自动切块 (Header-Preserved Chunking)")
  void testLargeHtmlTableChunkingWithHeaderPreserved() {
    properties.getChunk().setTargetChunkTokens(20);

    StringBuilder tableBuilder = new StringBuilder();
    tableBuilder.append("<table>\n");
    tableBuilder.append("  <tr><th>指标项</th><th>阈值</th><th>告警级别</th></tr>\n");
    for (int i = 1; i <= 15; i++) {
      tableBuilder.append(
          String.format("  <tr><td>指标_%d</td><td>%d ms</td><td>CRITICAL</td></tr>\n", i, i * 100));
    }
    tableBuilder.append("</table>\n");

    List<EnrichedDocChunk> results = enricher.enrichFromMarkdown("监控指标表", tableBuilder.toString());

    // 表格应当被切分成多个子表格块
    assertThat(results.size()).isGreaterThanOrEqualTo(2);
    for (EnrichedDocChunk chunk : results) {
      assertThat(chunk.chunkType()).isEqualTo("table");
      assertThat(chunk.content()).startsWith("<table>");
      assertThat(chunk.content()).endsWith("</table>");
      // 每一个切片都必须复制保留表头
      assertThat(chunk.content()).contains("<th>指标项</th><th>阈值</th><th>告警级别</th>");
    }
  }

  @Test
  @DisplayName("图文并茂 <figure> 插图卡片测试：整体保留原子块，嵌入文本剥离标签保留 OCR 内容")
  void testFigureImageOcrChunkPreserved() {
    String figureHtml =
        """
        # 系统架构概览

        系统核心结构如下图所示：

        <figure data-type="image_ocr">
          <img src="/static/figures/arch_01.png" alt="系统架构拓扑图" />
          <figcaption>图 1-1 分布式微服务架构拓扑图</figcaption>
          <blockquote>
            接入层：Nginx 反向代理；
            网关层：Spring Cloud Gateway 路由鉴权；
            服务层：LexiLoom 业务核心；
            存储层：RustFS & Elasticsearch。
          </blockquote>
        </figure>

        上述架构具备高可用与横向扩展能力。
        """;

    List<EnrichedDocChunk> results = enricher.enrichFromMarkdown("架构手册", figureHtml);

    // 验证包含 figure 类型的切块
    EnrichedDocChunk figureChunk =
        results.stream()
            .filter(c -> "figure".equals(c.chunkType()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("未找到 figure 切块"));

    // 存储与展示保留完整 HTML figure 与图片链接
    assertThat(figureChunk.content()).contains("<figure data-type=\"image_ocr\">");
    assertThat(figureChunk.content()).contains("src=\"/static/figures/arch_01.png\"");
    assertThat(figureChunk.content()).contains("<figcaption>图 1-1 分布式微服务架构拓扑图</figcaption>");
    assertThat(figureChunk.headerPath()).isEqualTo("系统架构概览");
    assertThat(figureChunk.contextPrefix()).contains("[类型: figure]");

    // 向量 embedding 剥离 HTML 标签但保留图注和 OCR 文本
    assertThat(figureChunk.enrichedText()).doesNotContain("<figure");
    assertThat(figureChunk.enrichedText()).doesNotContain("<img");
    assertThat(figureChunk.enrichedText()).contains("图 1-1 分布式微服务架构拓扑图");
    assertThat(figureChunk.enrichedText()).contains("接入层：Nginx 反向代理");
    assertThat(figureChunk.enrichedText()).contains("存储层：RustFS & Elasticsearch");
  }

  @Test
  @DisplayName("多级数字编号大纲感知与语义装箱测试：3 与 3.1 聚合为一个分片，3.2 与 3.2.1 另起分片，且超限时包含尾部 Overlap")
  void testHierarchicalSectionPackingWithNumberedHeadingsAndOverlap() {
    properties.getChunk().setTargetChunkTokens(120);
    properties.getChunk().setOverlapTokens(25);

    String docText =
        """
        2.3 条件与限制
        数据来源依赖元模型接口数据，审批流程依赖产品研发工作台。

        3. 总体方案设计
        3.1 系统总体结构设计
        在S16RTE编排系统中，操作人员首先创建脚本信息，随后建立对应的脚本版本。流程设计在可视化编排器中完成后提交审批。

        3.2 系统模块设计
        3.2.1 系统结构设计及模块划分
        S16RTE编排系统主要包括脚本管理、脚本详情与编排工具三大功能模块，支持全流程操作。
        脚本管理模块支持用户通过Web界面创建脚本，涉及SOA接口调用。

        3.2.2 编排工具模块设计
        RTE编排工具是个低代码流程编排工具，支持画布自由绘制与节点连线。
        """;

    List<EnrichedDocChunk> results = enricher.enrichFromMarkdown("详细设计书", docText);

    // 验证切块数量大于等于 2
    assertThat(results.size()).isGreaterThanOrEqualTo(2);

    // 验证 headerPath 成功识别了多级数字编号大纲，绝非默认的"正文"
    assertThat(results.stream().anyMatch(c -> c.headerPath().contains("2.3 条件与限制"))).isTrue();
    assertThat(results.stream().anyMatch(c -> c.headerPath().contains("3. 总体方案设计"))).isTrue();
    assertThat(results.stream().anyMatch(c -> c.headerPath().contains("3.2 系统模块设计"))).isTrue();

    // 验证 3 与 3.1 在同一个切片中
    EnrichedDocChunk sec3Chunk =
        results.stream()
            .filter(c -> c.content().contains("3.1 系统总体结构设计"))
            .findFirst()
            .orElseThrow();
    assertThat(sec3Chunk.content()).contains("3. 总体方案设计");
    assertThat(sec3Chunk.content()).contains("3.1 系统总体结构设计");

    // 验证 3.2 与 3.2.1 在同一个切片中且语义完整
    EnrichedDocChunk sec32Chunk =
        results.stream()
            .filter(c -> c.content().contains("3.2.1 系统结构设计及模块划分"))
            .findFirst()
            .orElseThrow();
    assertThat(sec32Chunk.content()).contains("3.2 系统模块设计");
    assertThat(sec32Chunk.content()).contains("脚本管理模块支持用户通过Web界面创建脚本");
  }

  @Test
  @DisplayName("结构化表格与自然章节归属测试：表格继承所在章节，并保留表格内部定义")
  void testGenericTableMetadataExtractionAndHierarchy() {
    String docMarkdown =
        """
        # 系统设计说明书

        3. 核心业务模块

        3.1 订单交易设计：负责处理用户下单与结算。

        <table>
        <tr><td>序号</td><td>字段名</td><td>数据类型</td><td>是否非空</td><td>默认值</td><td>字段说明</td></tr>
        <tr><td>1</td><td>id</td><td>bigint</td><td>是</td><td></td><td>订单主键</td></tr>
        <tr><td>2</td><td>order_no</td><td>varchar(64)</td><td>是</td><td></td><td>业务订单号</td></tr>
        <tr><td>3</td><td>status</td><td>tinyint(1)</td><td>是</td><td>0</td><td>订单状态</td></tr>
        </table>
        """;

    List<EnrichedDocChunk> results = enricher.enrichFromMarkdown("电商交易系统设计", docMarkdown);

    EnrichedDocChunk tableChunk =
        results.stream().filter(c -> "table".equals(c.chunkType())).findFirst().orElseThrow();

    assertThat(tableChunk.headerPath()).contains("3. 核心业务模块 / 3.1 订单交易设计");

    assertThat(tableChunk.contextPrefix())
        .contains("[章节: 3. 核心业务模块 / 3.1 订单交易设计]")
        .contains("[类型: table]");

    assertThat(tableChunk.content()).contains("order_no").contains("订单主键");
  }

  @Test
  @DisplayName("通用前置说明/引言段落与表格分离时自动向上吸纳融合，避免孤立碎片分块并保障表格上下文完整")
  void testPrecedingLeadInAbsorption() {
    String docMarkdown =
        """
        # 6. 中间件设计

        ## 6.1 数据库设计

        ### 6.1.4 MySQL数据库设计

        表名：script_version_info

        表注释：脚本版本表

        <table>
        <tr><th>序号</th><th>字段名</th><th>数据类型</th><th>是否非空</th><th>默认值</th><th>字段说明</th></tr>
        <tr><td>1</td><td>id</td><td>bigint</td><td>是</td><td>-</td><td>主键</td></tr>
        <tr><td>2</td><td>script_id</td><td>bigint</td><td>是</td><td>-</td><td>脚本id</td></tr>
        <tr><td>3</td><td>script_major_version</td><td>int</td><td>是</td><td>-</td><td>大版本</td></tr>
        </table>
        """;

    List<EnrichedDocChunk> results = enricher.enrichFromMarkdown("S16详细设计书", docMarkdown);

    // 1. 验证不存在孤立的前置碎片纯文本块
    assertThat(results.stream().filter(c -> "text".equals(c.chunkType())))
        .noneMatch(c -> c.content().contains("表名：script_version_info"));

    // 2. 验证表格切块成功吸纳了前置引导说明段落，且保持自然章节路径
    EnrichedDocChunk tableChunk =
        results.stream().filter(c -> "table".equals(c.chunkType())).findFirst().orElseThrow();

    assertThat(tableChunk.headerPath()).isEqualTo("6. 中间件设计 / 6.1 数据库设计 / 6.1.4 MySQL数据库设计");

    assertThat(tableChunk.contextPrefix())
        .contains("[章节: 6. 中间件设计 / 6.1 数据库设计 / 6.1.4 MySQL数据库设计]")
        .contains("[类型: table]");

    // 验证切块内容同时包含前置说明文字与完整字段数据
    assertThat(tableChunk.content())
        .contains("表名：script_version_info")
        .contains("表注释：脚本版本表")
        .contains("script_id")
        .contains("大版本");
  }
}
