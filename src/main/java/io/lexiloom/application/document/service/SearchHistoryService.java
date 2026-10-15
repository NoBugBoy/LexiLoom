package io.lexiloom.application.document.service;

import java.util.List;

/**
 * 用户搜索历史服务接口
 *
 * <p>支持多租户空间隔离 (workspaceId)，采用 Redis ZSet 热点缓存与异步领域事件双写归档机制。
 */
public interface SearchHistoryService {

  /**
   * 记录用户搜索历史 (热点写入 Redis ZSet，并发布异步归档事件)
   *
   * @param userId 用户 ID
   * @param workspaceId 空间 ID
   * @param keyword 搜索关键词
   */
  void record(Long userId, Long workspaceId, String keyword);

  /**
   * 获取用户指定工作区最近搜索历史 (倒序排列，默认 10 条)
   *
   * @param userId 用户 ID
   * @param workspaceId 工作区 ID (可为空，为空时查询全局历史)
   * @param size 获取条数
   * @return 搜索关键词列表
   */
  List<String> getHistory(Long userId, Long workspaceId, int size);

  /**
   * 获取用户最近搜索历史 (兼容旧版无 workspaceId 调用)
   *
   * @param userId 用户 ID
   * @param size 获取条数
   * @return 搜索关键词列表
   */
  default List<String> getHistory(Long userId, int size) {
    return getHistory(userId, null, size);
  }

  /**
   * 删除用户指定工作区单条搜索历史
   *
   * @param userId 用户 ID
   * @param workspaceId 工作区 ID (可为空)
   * @param keyword 搜索关键词
   */
  void delete(Long userId, Long workspaceId, String keyword);

  /**
   * 删除用户单条搜索历史 (兼容旧版调用)
   *
   * @param userId 用户 ID
   * @param keyword 搜索关键词
   */
  default void delete(Long userId, String keyword) {
    delete(userId, null, keyword);
  }

  /**
   * 清空用户指定工作区搜索历史
   *
   * @param userId 用户 ID
   * @param workspaceId 工作区 ID (可为空)
   */
  void clear(Long userId, Long workspaceId);

  /**
   * 清空用户搜索历史 (兼容旧版调用)
   *
   * @param userId 用户 ID
   */
  default void clear(Long userId) {
    clear(userId, null);
  }
}
