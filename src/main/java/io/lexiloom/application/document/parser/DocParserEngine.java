package io.lexiloom.application.document.parser;

/** 具体解析引擎策略接口 供 DeepDoc 等不同底层解析器实现 */
public interface DocParserEngine {

  /** 引擎类型 */
  DocParserEngineEnum getEngineType();

  /** 判断该引擎是否支持指定文件后缀 */
  boolean supports(String fileExtension);

  /**
   * 执行解析
   *
   * @param command 解析命令
   * @return 解析结果
   */
  DocParseResult parse(DocParseCommand command);

  /** 检测引擎是否已配置且可用（如远程服务端点、API Token 等是否已具备） */
  default boolean isAvailable() {
    return true;
  }
}
