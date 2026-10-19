package io.lexiloom.application.ai.search;

import cn.hutool.core.util.StrUtil;
import io.lexiloom.application.exception.BusinessException;
import java.util.List;
import java.util.Map;

/**
 * 双路混合检索与重排命令 (不可变 record)
 *
 * @param workspaceId 归属工作区 ID (多租户隔离核心凭证，必填)
 * @param query 用户提问或检索关键词 (必填)
 * @param queryVector 预计算问题语义向量 (若为 null 则由服务自动调用 EmbeddingGateway 嵌入)
 * @param topK 最终返回的优质切片数量上限 (默认 5)
 * @param minScore 最低相似度阈值过滤 (可选)
 * @param documentId 限制检索的特定文档 ID (可选，用于单文档问答)
 * @param tags 标签精准过滤列表 (可选)
 * @param categoryId 文档分类 ID 过滤 (可选)
 * @param userId 当前发起检索的用户 ID (用于 ACL 私密文档过滤，可选)
 * @param isAdmin 当前用户是否为工作空间管理员或以上 (管理员可查看空间内所有私密文档)
 */
public record HybridSearchCommand(
    Long workspaceId,
    String query,
    float[] queryVector,
    int topK,
    Double minScore,
    Long documentId,
    List<String> tags,
    Long categoryId,
    Long userId,
    boolean isAdmin,
    Map<Long, Integer> weights) {

  public HybridSearchCommand {
    if (workspaceId == null) {
      throw BusinessException.badRequest("工作区ID不能为空");
    }
    if (StrUtil.isBlank(query)) {
      throw BusinessException.badRequest("检索关键词不能为空");
    }
    query = query.trim();
    topK = topK > 0 ? topK : 5;
    tags = tags != null ? List.copyOf(tags) : List.of();
    weights = weights != null ? Map.copyOf(weights) : java.util.Collections.emptyMap();
  }

  /** 兼容 10 参数构造器 (默认 weights 为空) */
  public HybridSearchCommand(
      Long workspaceId,
      String query,
      float[] queryVector,
      int topK,
      Double minScore,
      Long documentId,
      List<String> tags,
      Long categoryId,
      Long userId,
      boolean isAdmin) {
    this(
        workspaceId,
        query,
        queryVector,
        topK,
        minScore,
        documentId,
        tags,
        categoryId,
        userId,
        isAdmin,
        java.util.Collections.emptyMap());
  }

  /** 兼容旧版 8 参数构造函数 (默认 userId=null, isAdmin=false, weights 为空) */
  public HybridSearchCommand(
      Long workspaceId,
      String query,
      float[] queryVector,
      int topK,
      Double minScore,
      Long documentId,
      List<String> tags,
      Long categoryId) {
    this(
        workspaceId,
        query,
        queryVector,
        topK,
        minScore,
        documentId,
        tags,
        categoryId,
        null,
        false,
        java.util.Collections.emptyMap());
  }

  public static HybridSearchCommand of(Long workspaceId, String query, int topK) {
    return new HybridSearchCommand(workspaceId, query, null, topK, null, null, List.of(), null);
  }

  public static HybridSearchCommand of(Long workspaceId, String query) {
    return of(workspaceId, query, 5);
  }

  public static HybridSearchCommand of(
      Long workspaceId, String query, float[] queryVector, int topK) {
    return new HybridSearchCommand(
        workspaceId, query, queryVector, topK, null, null, List.of(), null);
  }
}
