package com.knowflow.application.user.repository;

import com.knowflow.application.common.BaseRepository;
import com.knowflow.application.user.model.entity.WorkSpaceMemberEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 团队成员仓储 */
@Repository
public interface WorkSpaceMemberRepository extends BaseRepository<WorkSpaceMemberEntity> {

  Optional<WorkSpaceMemberEntity> findByWorkSpaceIdAndUserId(Long workSpaceId, Long userId);

  boolean existsByWorkSpaceIdAndUserId(Long workSpaceId, Long userId);

  long countByWorkSpaceId(Long workSpaceId);

  List<WorkSpaceMemberEntity> findByWorkSpaceId(Long workSpaceId);

  List<WorkSpaceMemberEntity> findByUserId(Long userId);

  void deleteByWorkSpaceIdAndUserId(Long workSpaceId, Long userId);

  void deleteByWorkSpaceId(Long workSpaceId);
}
