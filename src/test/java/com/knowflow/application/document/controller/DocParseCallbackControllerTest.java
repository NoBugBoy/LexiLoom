package com.knowflow.application.document.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.knowflow.application.document.model.request.DeepDocParseCallbackRequest;
import com.knowflow.application.document.service.DocParseCallbackService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@DisplayName("DocParseCallbackController 回调控制器测试")
class DocParseCallbackControllerTest {

  private DocParseCallbackService docParseCallbackService;
  private DocParseCallbackController controller;

  @BeforeEach
  void setUp() {
    docParseCallbackService = mock(DocParseCallbackService.class);
    controller = new DocParseCallbackController(docParseCallbackService);
  }

  @Test
  @DisplayName("接收有效解析回调：委托给 Service 处理并返回 200 success")
  void testOnParseCompleted() {
    DeepDocParseCallbackRequest request =
        new DeepDocParseCallbackRequest(
            200,
            "SUCCESS",
            "task_001",
            10L,
            20L,
            1L,
            1L,
            "sample.pdf",
            "pdf",
            "# 标题\n\n正文",
            "# 标题\n\n正文",
            10,
            List.of(),
            List.of(),
            null,
            null);

    ResponseEntity<Map<String, Object>> response = controller.onParseCompleted(request);

    verify(docParseCallbackService).handleCallback(request);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).containsEntry("code", 200);
    assertThat(response.getBody()).containsEntry("message", "success");
  }
}
