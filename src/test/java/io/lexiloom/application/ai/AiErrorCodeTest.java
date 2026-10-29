package io.lexiloom.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lexiloom.application.exception.ErrorCode;
import io.lexiloom.application.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AiErrorCodeTest {

  @Test
  @DisplayName("验证 ErrorCode.Ai 各枚举常量的状态码与描述规范")
  void testAiErrorCodes() {
    assertThat(ErrorCode.Ai.LLM_API_ERROR.getCode()).isEqualTo("LLM_API_ERROR");
    assertThat(ErrorCode.Ai.LLM_API_ERROR.getMessage()).isEqualTo("大模型接口远程调用失败");
    assertThat(ErrorCode.Ai.LLM_API_ERROR.getHttpStatus())
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

    assertThat(ErrorCode.Ai.EMBEDDING_FAILED.getCode()).isEqualTo("EMBEDDING_FAILED");
    assertThat(ErrorCode.Ai.EMBEDDING_FAILED.getMessage()).isEqualTo("文本向量化计算失败");
    assertThat(ErrorCode.Ai.EMBEDDING_FAILED.getHttpStatus())
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

    assertThat(ErrorCode.Ai.RERANK_FAILED.getCode()).isEqualTo("RERANK_FAILED");
    assertThat(ErrorCode.Ai.RERANK_FAILED.getMessage()).isEqualTo("重排模型调用失败");
    assertThat(ErrorCode.Ai.RERANK_FAILED.getHttpStatus())
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

    assertThat(ErrorCode.Ai.CHUNK_SPLIT_FAILED.getCode()).isEqualTo("CHUNK_SPLIT_FAILED");
    assertThat(ErrorCode.Ai.CHUNK_SPLIT_FAILED.getMessage()).isEqualTo("DeepDoc切块解析组装失败");
    assertThat(ErrorCode.Ai.CHUNK_SPLIT_FAILED.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);

    assertThat(ErrorCode.Ai.HYBRID_SEARCH_FAILED.getCode()).isEqualTo("HYBRID_SEARCH_FAILED");
    assertThat(ErrorCode.Ai.HYBRID_SEARCH_FAILED.getMessage()).isEqualTo("双路混合召回执行失败");
    assertThat(ErrorCode.Ai.HYBRID_SEARCH_FAILED.getHttpStatus())
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

    assertThat(ErrorCode.Ai.SESSION_NOT_FOUND.getCode()).isEqualTo("SESSION_NOT_FOUND");
    assertThat(ErrorCode.Ai.SESSION_NOT_FOUND.getMessage()).isEqualTo("对话会话不存在或已过期");
    assertThat(ErrorCode.Ai.SESSION_NOT_FOUND.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("验证 BusinessException 结合 ErrorCode.Ai 的抛出与捕获")
  void testBusinessExceptionWithAiErrorCode() {
    assertThatThrownBy(
            () -> {
              throw new BusinessException(ErrorCode.Ai.LLM_API_ERROR, "DeepSeek 服务连接超时");
            })
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex -> {
              BusinessException be = (BusinessException) ex;
              assertThat(be.getCode()).isEqualTo(ErrorCode.Ai.LLM_API_ERROR.getCode());
              assertThat(be.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
              assertThat(be.getMessage()).isEqualTo("DeepSeek 服务连接超时");
            });
  }
}
