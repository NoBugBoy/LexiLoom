package io.lexiloom.application.document.model.request;

import jakarta.validation.constraints.NotNull;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * DeepDoc 异步解析结果 HTTP 回调请求 DTO（不可变 Java record）
 *
 * @param code 响应码 (200 成功, 500 失败)
 * @param status 解析状态 ("SUCCESS", "FAILED", "PROCESSING")
 * @param taskId 异步任务唯一标识
 * @param documentId 目标文档主键 ID (必须)
 * @param sourceFileId 来源文件资产 ID
 * @param workspaceId 所属工作空间 ID
 * @param operatorId 操作人用户 ID
 * @param filename 文件原名
 * @param fileType 文件格式类型
 * @param markdown 提取的完整 Markdown 正文
 * @param content 兼容正文字段
 * @param totalChars 字符总数
 * @param chunks 结构化段落切片
 * @param blocks 结构化版面块
 * @param error 错误信息
 * @param message 消息描述
 */
public record DeepDocParseCallbackRequest(
    Integer code,
    String status,
    String taskId,
    @NotNull(message = "documentId 不能为空") Long documentId,
    Long sourceFileId,
    Long workspaceId,
    Long operatorId,
    String filename,
    String fileType,
    String markdown,
    String content,
    Integer totalChars,
    List<Map<String, Object>> chunks,
    List<Map<String, Object>> blocks,
    String error,
    String message) {

  public DeepDocParseCallbackRequest {
    chunks = chunks == null ? Collections.emptyList() : List.copyOf(chunks);
    blocks = blocks == null ? Collections.emptyList() : List.copyOf(blocks);
  }

  public boolean isSuccess() {
    return ("SUCCESS".equalsIgnoreCase(status) || (code != null && code == 200))
        && !"FAILED".equalsIgnoreCase(status);
  }
}
