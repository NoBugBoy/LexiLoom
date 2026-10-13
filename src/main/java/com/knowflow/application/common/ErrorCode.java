package com.knowflow.application.common;

import org.springframework.http.HttpStatus;

public interface ErrorCode {
  String getCode(); // 业务错误码 (如 USER_NOT_FOUND)

  String getMessage(); // 默认展示文案 (如 用户不存在)

  HttpStatus getHttpStatus(); // 对应的 HTTP 状态 (如 404 NOT_FOUND)

  // 默认内置一组常用枚举
  enum Common implements ErrorCode {
    BAD_REQUEST("BAD_REQUEST", "请求参数有误", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED("UNAUTHORIZED", "未登录或登录已过期", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("FORBIDDEN", "无权限访问", HttpStatus.FORBIDDEN),
    NOT_FOUND("NOT_FOUND", "资源不存在", HttpStatus.NOT_FOUND),
    CONFLICT("CONFLICT", "资源状态冲突", HttpStatus.CONFLICT),
    INTERNAL_ERROR("INTERNAL_ERROR", "系统开小差了，请稍后重试", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    Common(String code, String message, HttpStatus httpStatus) {
      this.code = code;
      this.message = message;
      this.httpStatus = httpStatus;
    }

    @Override
    public String getCode() {
      return code;
    }

    @Override
    public String getMessage() {
      return message;
    }

    @Override
    public HttpStatus getHttpStatus() {
      return httpStatus;
    }
  }

  // 文档与资产中心错误码
  enum Document implements ErrorCode {
    STATE_MACHINE_ERROR("STATE_MACHINE_ERROR", "文档状态流转失败", HttpStatus.BAD_REQUEST),
    UNSUPPORTED_DOC_TYPE("UNSUPPORTED_DOC_TYPE", "不支持的文档文件格式", HttpStatus.BAD_REQUEST),
    DOC_PARSE_FAILED("DOC_PARSE_FAILED", "文档解析转换失败", HttpStatus.BAD_REQUEST),
    DOC_NOT_FOUND("DOC_NOT_FOUND", "文档不存在或已被删除", HttpStatus.NOT_FOUND),
    DOC_VERSION_NOT_FOUND("DOC_VERSION_NOT_FOUND", "指定的版本快照不存在", HttpStatus.NOT_FOUND),
    DOC_ACCESS_DENIED("DOC_ACCESS_DENIED", "无权访问该文档", HttpStatus.FORBIDDEN),
    FILE_STORAGE_ERROR("FILE_STORAGE_ERROR", "文件存储服务异常", HttpStatus.INTERNAL_SERVER_ERROR),
    ES_INDEX_FAILED("ES_INDEX_FAILED", "ES 索引写入失败", HttpStatus.INTERNAL_SERVER_ERROR),
    ES_SEARCH_FAILED("ES_SEARCH_FAILED", "ES 检索执行失败", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    Document(String code, String message, HttpStatus httpStatus) {
      this.code = code;
      this.message = message;
      this.httpStatus = httpStatus;
    }

    @Override
    public String getCode() {
      return code;
    }

    @Override
    public String getMessage() {
      return message;
    }

    @Override
    public HttpStatus getHttpStatus() {
      return httpStatus;
    }
  }

  // AI 智能问答与 RAG 检索增强中心错误码
  enum Ai implements ErrorCode {
    LLM_API_ERROR("LLM_API_ERROR", "大模型接口远程调用失败", HttpStatus.INTERNAL_SERVER_ERROR),
    EMBEDDING_FAILED("EMBEDDING_FAILED", "文本向量化计算失败", HttpStatus.INTERNAL_SERVER_ERROR),
    RERANK_FAILED("RERANK_FAILED", "重排模型调用失败", HttpStatus.INTERNAL_SERVER_ERROR),
    CHUNK_SPLIT_FAILED("CHUNK_SPLIT_FAILED", "DeepDoc切块解析组装失败", HttpStatus.BAD_REQUEST),
    HYBRID_SEARCH_FAILED("HYBRID_SEARCH_FAILED", "双路混合召回执行失败", HttpStatus.INTERNAL_SERVER_ERROR),
    SESSION_NOT_FOUND("SESSION_NOT_FOUND", "对话会话不存在或已过期", HttpStatus.NOT_FOUND);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    Ai(String code, String message, HttpStatus httpStatus) {
      this.code = code;
      this.message = message;
      this.httpStatus = httpStatus;
    }

    @Override
    public String getCode() {
      return code;
    }

    @Override
    public String getMessage() {
      return message;
    }

    @Override
    public HttpStatus getHttpStatus() {
      return httpStatus;
    }
  }
}
