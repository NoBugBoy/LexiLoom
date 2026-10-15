package io.lexiloom.application.document.parser;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.common.ErrorCode;
import io.lexiloom.application.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 通用文档解析网关门面实现（防腐层 ACL） 策略规则： 1. 若为 AUTO（默认）：优先使用 DeepDoc 深度解析引擎； 2. 若显式指定具体引擎（非
 * AUTO），优先使用指定引擎；若指定引擎未配置则尝试使用其他已配置的引擎； 3. 启动自检：若 DeepDoc 等解析引擎未配置，则启动报错，提示配置解析引擎服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultDocParserGateway implements DocParserGateway {

  private final List<DocParserEngine> engines;

  @Value("${lexiloom.parser.default-engine:AUTO}")
  private String defaultEngineConfig;

  @PostConstruct
  public void validateEngines() {
    boolean anyConfigured =
        engines != null && engines.stream().anyMatch(DocParserEngine::isAvailable);
    if (!anyConfigured) {
      throw new IllegalStateException(
          "系统未配置任何文档解析引擎服务！请配置 DeepDoc (lexiloom.parser.deepdoc.endpoint)。");
    }
    log.info(
        "【DocParserGateway】文档解析网关初始化完成: 注册引擎数量={}, 可用引擎={}",
        engines.size(),
        engines.stream()
            .filter(DocParserEngine::isAvailable)
            .map(e -> e.getEngineType().name())
            .toList());
  }

  @Override
  public DocParseResult parse(DocParseCommand command) {
    if (command == null) {
      throw new BusinessException(ErrorCode.Common.BAD_REQUEST, "解析命令不能为空");
    }

    String ext = command.fileExtension();
    if (StrUtil.isBlank(ext)) {
      throw new BusinessException(
          ErrorCode.Document.UNSUPPORTED_DOC_TYPE, "无法识别的文件扩展名: " + command.fileName());
    }

    DocParserEngine selectedEngine = routeEngine(command);
    log.info(
        "【DocParserGateway】路由解析请求: file={}, ext={}, 匹配引擎={}",
        command.fileName(),
        ext,
        selectedEngine.getEngineType());

    return selectedEngine.parse(command);
  }

  @Override
  public boolean supportsExtension(String fileExtension) {
    if (StrUtil.isBlank(fileExtension)) {
      return false;
    }
    return engines.stream().anyMatch(e -> e.supports(fileExtension));
  }

  private DocParserEngine routeEngine(DocParseCommand command) {
    String ext = command.fileExtension().toLowerCase();

    DocParserEngine deepDocEngine = findEngine(DocParserEngineEnum.DEEPDOC);
    boolean deepDocAvailable = deepDocEngine != null && deepDocEngine.isAvailable();

    // 1. 若显式指定具体引擎（非 AUTO，即手动指定）
    DocParserEngineEnum preferred = command.preferredEngine();
    if (preferred != null && preferred != DocParserEngineEnum.AUTO) {
      DocParserEngine target = findEngine(preferred);
      if (target != null && target.isAvailable()) {
        return target;
      }
      // 手动指定的引擎未配置/不可用，自动切换至其他可用引擎
      log.warn("【DocParserGateway】手动指定的引擎 [{}] 未配置或不可用，正在自动切换可用引擎", preferred);
      if (preferred != DocParserEngineEnum.DEEPDOC
          && deepDocAvailable
          && deepDocEngine.supports(ext)) {
        return deepDocEngine;
      }
      if (target != null) {
        return target;
      }
    }

    // 2. 检查全局配置策略（若配置了非 AUTO 的默认引擎）
    if (StrUtil.isNotBlank(defaultEngineConfig) && !"AUTO".equalsIgnoreCase(defaultEngineConfig)) {
      try {
        DocParserEngineEnum configEngine =
            DocParserEngineEnum.valueOf(defaultEngineConfig.toUpperCase());
        DocParserEngine target = findEngine(configEngine);
        if (target != null && target.isAvailable()) {
          return target;
        }
      } catch (Exception ignored) {
      }
    }

    // 3. AUTO 模式：默认优先使用 DeepDoc（若可用且支持该文件）
    if (deepDocAvailable && deepDocEngine.supports(ext)) {
      return deepDocEngine;
    }

    // 容错兜底：若扩展名暂不在各引擎静态列表中，但引擎可用
    if (deepDocAvailable) {
      return deepDocEngine;
    }

    DocParserEngine fallbackEngine =
        engines.stream()
            .filter(DocParserEngine::isAvailable)
            .filter(e -> e.supports(ext))
            .findFirst()
            .orElse(null);
    if (fallbackEngine != null) {
      return fallbackEngine;
    }

    // 4. 全部未配置
    throw new BusinessException(
        ErrorCode.Document.DOC_PARSE_FAILED, "系统未配置任何可用的文档解析引擎服务！请检查 DeepDoc 的服务配置。");
  }

  private DocParserEngine findEngine(DocParserEngineEnum engineType) {
    if (engines == null || engineType == null) {
      return null;
    }
    return engines.stream().filter(e -> e.getEngineType() == engineType).findFirst().orElse(null);
  }
}
