package com.knowflow.application.document.repository;

import com.knowflow.application.common.BaseRepository;
import com.knowflow.application.document.model.entity.SearchHistoryEntity;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 搜索历史归档仓储 */
@Repository
public interface SearchHistoryRepository extends BaseRepository<SearchHistoryEntity> {

  /**
   * 按用户 ID 倒序查询历史搜索归档记录
   *
   * @param userId 用户 ID
   * @return 历史搜索归档列表
   */
  List<SearchHistoryEntity> findByUserIdOrderBySearchAtDesc(Long userId);

  /**
   * 按工作区与用户 ID 倒序查询历史搜索归档记录
   *
   * @param workspaceId 空间 ID
   * @param userId 用户 ID
   * @return 历史搜索归档列表
   */
  List<SearchHistoryEntity> findByWorkspaceIdAndUserIdOrderBySearchAtDesc(
      Long workspaceId, Long userId);
}
