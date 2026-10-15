package io.lexiloom.application.document.controller;

import io.lexiloom.application.document.model.request.DeepDocParseCallbackRequest;
import io.lexiloom.application.document.service.DocParseCallbackService;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** DeepDoc 异步解析结果 HTTP 回调接收控制器 开放免鉴权端点，供 DeepDoc 微服务解析完成后异步通知 Java 后端。 */
@Slf4j
@Validated
@RestController
@RequestMapping({"/api/documents/parse-callback", "/documents/parse-callback"})
@RequiredArgsConstructor
public class DocParseCallbackController {

  private final DocParseCallbackService docParseCallbackService;

  /**
   * 接收 DeepDoc 异步解析完成回调
   *
   * @param request 回调请求载荷
   * @return 处理结果
   */
  @PostMapping
  public ResponseEntity<Map<String, Object>> onParseCompleted(
      @Valid @RequestBody DeepDocParseCallbackRequest request) {
    log.info(
        "【DocParseCallbackController】收到 DeepDoc 解析回调请求: docId={}, status={}, taskId={}",
        request.documentId(),
        request.status(),
        request.taskId());
    docParseCallbackService.handleCallback(request);
    return ResponseEntity.ok(Map.of("code", 200, "message", "success"));
  }
}
