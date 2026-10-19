package io.lexiloom.application.document.spacelink.repository;

import io.lexiloom.application.common.BaseRepository;
import io.lexiloom.application.document.spacelink.model.entity.WorkSpaceSpaceLinkEntity;
import io.lexiloom.application.enums.SpaceLinkStatusEnum;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 工作区横向显式关联仓储接口 */
@Repository
public interface WorkSpaceSpaceLinkRepository extends BaseRepository<WorkSpaceSpaceLinkEntity> {

  /** 根据源空间与目标空间查询关联记录 */
  Optional<WorkSpaceSpaceLinkEntity> findBySourceWorkspaceIdAndTargetWorkspaceId(
      Long sourceWorkspaceId, Long targetWorkspaceId);

  /** 判断是否存在关联记录 */
  boolean existsBySourceWorkspaceIdAndTargetWorkspaceId(
      Long sourceWorkspaceId, Long targetWorkspaceId);

  /** 查询源空间指定状态的所有关联列表 */
  List<WorkSpaceSpaceLinkEntity> findBySourceWorkspaceIdAndStatus(
      Long sourceWorkspaceId, SpaceLinkStatusEnum status);

  /** 查询源空间所有关联列表 */
  List<WorkSpaceSpaceLinkEntity> findBySourceWorkspaceId(Long sourceWorkspaceId);

  /** 查询目标空间指定状态的所有被关联列表 (供待审批查询) */
  List<WorkSpaceSpaceLinkEntity> findByTargetWorkspaceIdAndStatus(
      Long targetWorkspaceId, SpaceLinkStatusEnum status);

  /** 查询目标空间所有被关联列表 */
  List<WorkSpaceSpaceLinkEntity> findByTargetWorkspaceId(Long targetWorkspaceId);
}
