package io.lexiloom.application.document.parser.engine;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.common.storage.FileStorageGateway;
import io.lexiloom.application.document.parser.DeepDocCoordinateUtil;
import io.lexiloom.application.document.parser.DeepDocParsedDocument;
import io.lexiloom.application.document.parser.DocParseCommand;
import io.lexiloom.application.document.parser.DocParseResult;
import io.lexiloom.application.document.parser.DocParserEngine;
import io.lexiloom.application.document.parser.DocParserEngineEnum;
import io.lexiloom.application.document.api.DocumentContentCleaner;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * RAGFlow DeepDoc 多格式解析引擎（真实 HTTP 远程微服务适配器）
 *
 * <p>对接部署在 Docker 中的 DeepDoc 独立服务 (POST /api/v1/parse)。 专注于
 * PDF、Word(docx)、PPT(pptx)、Excel(xlsx/xls)、纯文本(txt/md) 的深度结构化解析、表格还原与智能切片，提取高质量 Markdown 与 Chunks。
 */
@Slf4j
@Component
public class DeepDocDocParserEngine implements DocParserEngine {

  private final FileStorageGateway fileStorageGateway;
  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  @Value("${lexiloom.parser.deepdoc.endpoint:}")
  private String deepdocEndpoint;

  @Value("${lexiloom.parser.deepdoc.callback-url:}")
  private String callbackUrl;

  private static final Set<String> SUPPORTED_EXTS =
      Set.of("pdf", "docx", "pptx", "xlsx", "xls", "txt", "md");

  @Autowired
  public DeepDocDocParserEngine(
      @Autowired(required = false) FileStorageGateway fileStorageGateway,
      @Autowired(required = false) ObjectMapper objectMapper) {
    this.fileStorageGateway = fileStorageGateway;
    this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();

    HttpClient httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofSeconds(120));
    this.restClient = RestClient.builder().requestFactory(requestFactory).build();
  }

  public DeepDocDocParserEngine() {
    this(null, null);
  }

  public DeepDocDocParserEngine(FileStorageGateway fileStorageGateway) {
    this(fileStorageGateway, null);
  }

  @Override
  public DocParserEngineEnum getEngineType() {
    return DocParserEngineEnum.DEEPDOC;
  }

  @Override
  public boolean supports(String fileExtension) {
    return StrUtil.isNotBlank(fileExtension)
        && SUPPORTED_EXTS.contains(fileExtension.trim().toLowerCase());
  }

  @Override
  public boolean isAvailable() {
    return StrUtil.isNotBlank(deepdocEndpoint);
  }

  @Override
  public DocParseResult parse(DocParseCommand command) {
    String fileName =
        StrUtil.blankToDefault(command.fileName(), "document." + command.fileExtension());
    String baseTitle = StrUtil.subBefore(fileName, ".", true);

    // 1. 若未配置 DeepDoc 服务端点
    if (StrUtil.isBlank(deepdocEndpoint)) {
      return DocParseResult.failure(
          getEngineType(), "DeepDoc 远程服务端点未配置 (lexiloom.parser.deepdoc.endpoint)");
    }

    // 2. 读取文件字节流
    byte[] fileBytes;
    try {
      if (command.contentStream() != null) {
        fileBytes = IoUtil.readBytes(command.contentStream());
      } else if (StrUtil.isNotBlank(command.storagePath()) && fileStorageGateway != null) {
        try (InputStream in = fileStorageGateway.download(command.storagePath())) {
          fileBytes = IoUtil.readBytes(in);
        }
      } else {
        return DocParseResult.failure(getEngineType(), "未提供文件数据或有效存储路径");
      }
    } catch (Exception e) {
      log.error("【DeepDoc】读取待解析文件数据失败: file={}, error={}", fileName, e.getMessage(), e);
      return DocParseResult.failure(getEngineType(), "读取文件失败: " + e.getMessage());
    }

    String url = normalizeEndpointUrl(deepdocEndpoint);
    log.info(
        "【DeepDoc】调用真实远程微服务解析: endpoint={}, file={}, size={} bytes",
        url,
        fileName,
        fileBytes.length);

    // 3. 执行真实 HTTP POST multipart 请求（内置冷启动/瞬时断开重试）
    String responseBody = null;
    Exception lastError = null;
    int maxAttempts = 2;

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource resource =
            new ByteArrayResource(fileBytes) {
              @Override
              public String getFilename() {
                return fileName;
              }
            };
        body.add("file", resource);

        String effectiveCallbackUrl =
            command.extraOptions().get("callbackUrl") != null
                ? String.valueOf(command.extraOptions().get("callbackUrl"))
                : callbackUrl;
        if (StrUtil.isNotBlank(effectiveCallbackUrl)) {
          body.add("callback_url", effectiveCallbackUrl);
          if (command.extraOptions().get("documentId") != null) {
            body.add("document_id", String.valueOf(command.extraOptions().get("documentId")));
          }
          if (command.extraOptions().get("sourceFileId") != null) {
            body.add("source_file_id", String.valueOf(command.extraOptions().get("sourceFileId")));
          }
          if (command.extraOptions().get("workspaceId") != null) {
            body.add("workspace_id", String.valueOf(command.extraOptions().get("workspaceId")));
          }
          if (command.extraOptions().get("operatorId") != null) {
            body.add("operator_id", String.valueOf(command.extraOptions().get("operatorId")));
          }
        }

        responseBody =
            restClient
                .post()
                .uri(url)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(String.class);
        lastError = null;
        break;
      } catch (Exception e) {
        lastError = e;
        if (attempt < maxAttempts && isTransientNetworkError(e)) {
          log.warn(
              "【DeepDoc】微服务调用发生瞬时网络中断或未就绪，准备重试: attempt={}, url={}, error={}",
              attempt,
              url,
              e.getMessage());
          try {
            Thread.sleep(1000);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      }
    }

    if (lastError != null) {
      log.error(
          "【DeepDoc】远程微服务调用失败: endpoint={}, file={}, error={}",
          url,
          fileName,
          lastError.getMessage(),
          lastError);
      String errorMsg = lastError.getMessage();
      if (isTransientNetworkError(lastError)) {
        errorMsg = "DeepDoc 远程服务未就绪或连接断开 (请检查 Docker 容器 deepdoc-server 是否完全就绪): " + errorMsg;
      }
      return DocParseResult.failure(getEngineType(), "调用 DeepDoc 远程服务异常: " + errorMsg);
    }

    try {
      // 检查是否为异步接收成功的响应 (PROCESSING)
      if (StrUtil.isNotBlank(responseBody) && responseBody.trim().startsWith("{")) {
        JsonNode rootNode = objectMapper.readTree(responseBody);
        String respStatus = rootNode.hasNonNull("status") ? rootNode.get("status").asString() : "";
        if ("PROCESSING".equalsIgnoreCase(respStatus) || "ACCEPTED".equalsIgnoreCase(respStatus)) {
          String taskId = rootNode.hasNonNull("task_id") ? rootNode.get("task_id").asString() : null;
          log.info("【DeepDoc】远程微服务已接收异步解析任务，进入等待回调模式: file={}, taskId={}", fileName, taskId);
          Map<String, Object> meta = new HashMap<>(command.extraOptions());
          if (taskId != null) {
            meta.put("taskId", taskId);
          }
          return DocParseResult.asyncProcessing(getEngineType(), null, taskId, meta);
        }
      }

      DeepDocParsedDocument parsedDoc = DeepDocCoordinateUtil.parseResponse(responseBody);
      String rawMarkdown = parsedDoc.markdown();
      String markdownWithStorageUrls = transferFiguresToStorage(rawMarkdown);
      String markdown = DocumentContentCleaner.cleanEmbeddedImages(markdownWithStorageUrls);
      String rawText = DocumentContentCleaner.extractRawText(markdown);
      String actualFileName =
          StrUtil.isNotBlank(parsedDoc.filename()) ? parsedDoc.filename() : fileName;
      String suggestedTitle =
          extractTitleFromMarkdown(markdown, StrUtil.subBefore(actualFileName, ".", true));

      Map<String, Object> meta = new HashMap<>(command.extraOptions());
      meta.put("fileName", actualFileName);
      meta.put("fileType", parsedDoc.fileType());
      meta.put("engine", getEngineType().name());
      meta.put("endpoint", deepdocEndpoint);
      meta.put("wordCount", markdown != null ? markdown.length() : 0);
      meta.put("totalChunks", parsedDoc.totalChunks());
      meta.put("chunks", parsedDoc.chunkMaps());
      meta.put("blocks", parsedDoc.blockMaps());
      meta.put("boxCount", parsedDoc.boxes().size());
      meta.put("boxMetadata", parsedDoc.boxMaps());

      log.info(
          "【DeepDoc】远程微服务解析成功: file={}, title={}, wordCount={}, boxCount={}",
          actualFileName,
          suggestedTitle,
          markdown != null ? markdown.length() : 0,
          parsedDoc.boxes().size());
      return DocParseResult.success(markdown, rawText, suggestedTitle, getEngineType(), meta);
    } catch (Exception e) {
      log.error(
          "【DeepDoc】解析微服务响应数据失败: endpoint={}, file={}, error={}", url, fileName, e.getMessage(), e);
      return DocParseResult.failure(getEngineType(), "解析 DeepDoc 响应异常: " + e.getMessage());
    }
  }

  private boolean isTransientNetworkError(Exception e) {
    if (e == null) {
      return false;
    }
    String msg = String.valueOf(e.getMessage()).toLowerCase();
    return msg.contains("header parser received no bytes")
        || msg.contains("connection refused")
        || msg.contains("reset by peer")
        || msg.contains("connection reset")
        || msg.contains("socketexception");
  }

  private String normalizeEndpointUrl(String endpoint) {
    String clean = endpoint.trim().replaceAll("/+$", "");
    if (!clean.contains("/api/v1/parse") && !clean.contains("/parse")) {
      return clean + "/api/v1/parse?format=json";
    }
    if (!clean.contains("format=")) {
      return clean.contains("?") ? clean + "&format=json" : clean + "?format=json";
    }
    return clean;
  }

  private String extractMarkdownFromBody(String body) {
    if (StrUtil.isBlank(body)) {
      return "";
    }
    String trimmed = body.trim();
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
      try {
        JsonNode node = objectMapper.readTree(trimmed);
        if (node.hasNonNull("markdown")) return node.get("markdown").asString();
        if (node.hasNonNull("text")) return node.get("text").asString();
        if (node.hasNonNull("content")) return node.get("content").asString();
        if (node.hasNonNull("data") && node.get("data").isString()) {
          return node.get("data").asString();
        }
      } catch (Exception ignored) {
      }
    }
    return body;
  }

  public static String extractTitleFromMarkdown(String content, String defaultTitle) {
    if (StrUtil.isBlank(content)) {
      return defaultTitle;
    }
    for (String line : content.split("\n")) {
      String trimmed = line.trim();
      if (trimmed.startsWith("# ")) {
        return trimmed.substring(2).trim();
      }
      if (trimmed.startsWith("## ")) {
        return trimmed.substring(3).trim();
      }
    }
    return defaultTitle;
  }

  private static final Pattern FIGURE_IMAGE_PATTERN =
      Pattern.compile("src=[\"'](/static/figures/([^\"']+))[\"']");

  /**
   * 将正文中的 DeepDoc 临时插图转存至 RustFS 对象存储，并将相对路径覆盖为可公开访问的 HTTP URL
   *
   * @param content 原始包含 /static/figures/... 相对路径的正文
   * @return 替换为 RustFS HTTP URL 后的正文
   */
  public String transferFiguresToStorage(String content) {
    if (StrUtil.isBlank(content)
        || fileStorageGateway == null
        || StrUtil.isBlank(deepdocEndpoint)) {
      return content;
    }

    Matcher matcher = FIGURE_IMAGE_PATTERN.matcher(content);
    if (!matcher.find()) {
      return content;
    }

    matcher.reset();
    Map<String, String> urlMapping = new HashMap<>();
    String baseEndpoint = normalizeEndpointUrl(deepdocEndpoint);
    String rootUrl = StrUtil.subBefore(baseEndpoint, "/api/v1/parse", true);
    if (StrUtil.isBlank(rootUrl)) {
      rootUrl = baseEndpoint.replaceAll("/+$", "");
    }

    while (matcher.find()) {
      String originalPath = matcher.group(1); // 例如 /static/figures/63a8a69f6f5e_fig_9.png
      String filename = matcher.group(2); // 例如 63a8a69f6f5e_fig_9.png

      if (urlMapping.containsKey(originalPath)) {
        continue;
      }

      try {
        String downloadUrl = rootUrl + originalPath;
        byte[] imgBytes = restClient.get().uri(downloadUrl).retrieve().body(byte[].class);

        if (imgBytes != null && imgBytes.length > 0) {
          String objectKey = "figures/" + filename;
          try (InputStream in = new ByteArrayInputStream(imgBytes)) {
            String httpUrl = fileStorageGateway.upload(objectKey, in, "image/png", imgBytes.length);
            urlMapping.put(originalPath, httpUrl);
            log.info("【DeepDoc】成功将插图转存至 RustFS 对象存储: {} -> {}", originalPath, httpUrl);
          }
        }
      } catch (Exception e) {
        log.warn("【DeepDoc】转存插图到 RustFS 失败 (保持原路径容灾): file={}, error={}", filename, e.getMessage());
      }
    }

    String result = content;
    for (Map.Entry<String, String> entry : urlMapping.entrySet()) {
      result = result.replace(entry.getKey(), entry.getValue());
    }
    return result;
  }
}
