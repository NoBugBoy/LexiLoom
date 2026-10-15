package io.lexiloom.application.document.parser;

/** 文档解析通用网关接口（防腐层 ACL） 对上层业务屏蔽底层解析引擎（如 DeepDoc 等）的技术细节与差异， 提供统一且高内聚的文档摄入与 Markdown 提取能力。 */
public interface DocParserGateway {

  /**
   * 执行文档解析并转换为标准化 Markdown
   *
   * @param command 解析命令（含文件流/路径、格式、可选引擎倾向及参数）
   * @return 标准解析结果
   */
  DocParseResult parse(DocParseCommand command);

  /**
   * 校验当前系统是否支持指定文件格式的解析
   *
   * @param fileExtension 文件后缀（如 docx, pdf, md）
   * @return 是否支持
   */
  boolean supportsExtension(String fileExtension);
}
