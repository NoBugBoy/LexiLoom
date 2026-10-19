package io.lexiloom.application.document.spacelink.repository;

import io.lexiloom.application.document.spacelink.model.entity.WorkSpaceSpaceLinkDocEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 空间横向关联授权文档明细仓储
 */
@Repository
public interface WorkSpaceSpaceLinkDocRepository
    extends JpaRepository<WorkSpaceSpaceLinkDocEntity, Long> {

  /** 查询某个 SpaceLink 授权的所有文档记录 */
  List<WorkSpaceSpaceLinkDocEntity> findByLinkId(Long linkId);

  /** 批量查询多个 SpaceLink 授权的所有文档记录 */
  List<WorkSpaceSpaceLinkDocEntity> findByLinkIdIn(Collection<Long> linkIds);

  /** 根据 linkId 清理关联授权明细 (重新申请或删除时使用) */
  void deleteByLinkId(Long linkId);
}
